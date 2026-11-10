# 最短演示：Java AI Agent 接入 Splunk AO（Galileo）

> **一句话**：Observe（观测）靠**启动参数 + 框架自带的监听机制**自动完成，业务代码几乎不动；Control（管控）只在**必须拦得住的业务关口**加少量代码。

本文写给第一次接触 Java 或 Splunk Agent Observability（AO，Galileo）的读者，5 分钟读完。实现细节见 [`JAVA-INSTRUMENTATION.md`](JAVA-INSTRUMENTATION.md)，验收证据见 [`VALIDATION.md`](VALIDATION.md)。

## 1. 先认识 5 个词

- **Java Agent（`-javaagent`）**：JVM 启动时挂载的采集器。本项目用 OpenTelemetry（OTel，开放遥测标准）官方提供的版本：不改代码就能记录 HTTP 请求、维护调用上下文，并把数据发出去。
- **Listener（监听器）**：框架自带的“发生了什么就通知你”的机制。LangChain4j（Java 的 AI 应用框架）在调用模型、调用 Agent、执行 Tool 的前后都会通知监听器。
- **Span（步骤记录）**：一个步骤的开始、结束、耗时和属性。一次请求的所有 Span 组成一棵树，叫 Trace。
- **OTLP（OpenTelemetry Protocol）**：Span 发往 Splunk AO 时使用的标准上报格式。
- **Control Adapter（安全门）**：业务步骤执行前/后询问 Agent Control“能不能做”，再由 Java 真正放行、改写或拦截。

## 2. Observe：三层，从自动到手工

| 层 | 谁提供 | 做什么 | 需要写代码吗 |
|---|---|---|---|
| ① OTel 官方 Java Agent | OTel 官方 | 每个 HTTP 请求一个 Span（Trace 的根）；调用上下文；OTLP 导出、批量发送、Resource | **不需要**：启动时加 `-javaagent` 和几个标准 `OTEL_*` 变量 |
| ② LangChain4j 官方扩展点 | LangChain4j 官方 + 本项目 1 个适配类 | 模型调用 → LLM Span（含真实 token）；AI Service → Agent Span；Tool 执行 → Tool Span | **注册一次**：`LangChain4jOtelBridge` 把框架事件翻译成 AO 能识别的 GenAI Span（`gen_ai.*`），在模型和 AI Service 的构建处各注册一次 |
| ③ 极少量手工 Span | 本项目 | 一轮对话的根 Agent（`ChatService`）、UI 点击确认扣款（`PaymentController`） | **2 处**：框架里没有对应事件，见第 6 节 |

需要如实说明：OTel 官方 Java Agent 自带的 GenAI 采集只支持 OpenAI 官方 Java SDK（`openai-java`），**并不理解 LangChain4j 的 Agent 和 Tool**；本项目通过 LangChain4j 调用 OpenAI，Java Agent 单独只能看到一个普通的 HTTP 请求。所以第 ② 层是必要的，而且它用的是框架的官方扩展点，不是逐个业务类手写 Span。

## 3. 本项目里只需 3 条命令

```bash
scripts/preflight.sh                       # 只读检查：Java、OpenAI、AO 配置（不打印密钥）
scripts/run.sh --background --observe      # 挂载 OTel Java Agent（首次自动下载并校验 SHA-256）
scripts/galileo-smoke.sh --yes             # 3 个只读问题 + 从 AO 回读 Trace 树（真实 OpenAI，产生少量费用）
```

私有配置 `~/.config/bank-agent-java/galileo.properties` 只需填两项：`AO_REALM`、`AO_O11Y_TOKEN`。

到 AO Console 查看：Project `bank-agent-java` → Agent Stream `bank-java-demo` → **Tracing**。每一轮对话是一棵树（2026-11-09 在线回读的真实结构）：

```text
http   POST /api/chat                    ← ① Java Agent 自动
└─ agent  invoke_agent BankingAssistant  ← ③ 手工根 Span（会话 ID → AO Session）
   ├─ llm    chat gpt-5-nano             ← ② 模型监听（路由；token 668/170）
   └─ agent  invoke_agent AccountAgent   ← ② AI Service 监听
      ├─ llm   chat gpt-5-nano           ← ② 模型监听（token 699/26）
      ├─ tool  execute_tool getAccountBalance   ← ② Tool 前后钩子
      └─ llm   chat gpt-5-nano           ← ② 模型监听（token 788/175）
```

同一会话的多轮对话（包括之后点击“确认”产生的独立 Trace）在同一个 **Session** 中。

**在你自己的 Java 应用里**，第 ① 层的全部“接入”就是这几行（值换成你的 realm、Token 和名称）：

```bash
export OTEL_SERVICE_NAME=my-agent
export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=https://ingest.<realm>.observability.splunkcloud.com/v2/trace/otlp
export OTEL_EXPORTER_OTLP_TRACES_HEADERS="X-SF-Token=<token>,project=<project>,logstream=<agent stream>"
export OTEL_RESOURCE_ATTRIBUTES="splunk_ao.project.name=<project>,splunk_ao.logstream.name=<agent stream>"
export OTEL_METRICS_EXPORTER=none OTEL_LOGS_EXPORTER=none
java -javaagent:opentelemetry-javaagent.jar -jar my-agent.jar
```

注意 AO 的端点是 `/v2/trace/otlp`（不是通用 Collector 的 `/v1/traces`），路由头是 `logstream`（不是 `agentstream`）。第 ② 层：把 `LangChain4jOtelBridge` 复制进项目，然后：

```java
OpenAiChatModel.builder()...listeners(List.of(otel))          // LLM Span
AiServices.builder(MyAgent.class)...
        .registerListeners(otel.aiServiceListeners())          // Agent Span
        .beforeToolExecution(otel::toolStarted)                // Tool Span 开始（Tool 方法运行之前）
        .afterToolExecution(otel::toolFinished)                // Tool Span 结束
```

## 4. Control：只在业务关口手工

- Console 中的规则**不会**自动拦截 Java 程序：Agent Control 服务只返回决策（DENY / STEER / OBSERVE），**由 Java 在步骤之前（PRE）和之后（POST）调用评估接口并真正执行**。
- 本项目手工接入的关口：用户消息与最终回答（`ChatService`）、模型调用（`GovernedChatModel`）、Tool 执行前后（`GovernedToolExecutor`）、确认扣款之前（`PaymentController`）。
- 为什么不能靠 Listener 自动完成：监听器和 `afterToolExecution` 是**事后通知**，写操作已经发生，无法阻止；DENY 必须在 Tool 方法和扣款**之前**生效。
- 开启：`scripts/run.sh --background --observe --control`。Control 不依赖 Observe：只开 `--control` 时拦截照常生效，只是 Console 中没有 Trace。

## 5. 为什么 Controls 标签还需要 Control Span

- 评估接口只返回决策，**不会**在 AO 中留下任何记录。
- 本项目用 OTel 公共 API 把每个评估结果写成一个 control span（属性与官方 Python SDK 相同），挂在被检查的步骤下面，**由同一个 Java Agent、同一条 OTLP 通道**发往 AO；Console 的 **Controls** 标签读的就是它。这不是另一个上报接口。
- 只开 Control、不开 Observe 时，没有 Java Agent，就没有 control span：决策照常执行、UI 中可见，但 Console 中没有 Controls 记录。

## 6. 一张表看清

| 能力 | 自动化来源 | 客户要改业务代码吗 | 为什么不能（完全）自动 | 在 AO 哪里验证 |
|---|---|---|---|---|
| HTTP 请求、上下文、OTLP 导出 | OTel 官方 Java Agent | 否 | — | Trace 根节点 `http POST /api/chat` |
| LLM 调用、token | LangChain4j `ChatModelListener` + 适配类 | 否（注册一次） | Java Agent 不认识 LangChain4j 的模型调用 | `llm` 节点的 token、模型名、耗时 |
| Agent（AI Service） | LangChain4j AI Service 监听 + 适配类 | 否（注册一次） | 同上 | `agent` 节点 |
| Tool | LangChain4j `before/afterToolExecution` + 适配类 | 否（注册一次） | 同上 | `tool` 节点（参数默认只导出参数名） |
| 一轮对话的根、会话 | 本项目手工 1 处（`ChatService`） | 是，约 9 行 | 一轮 = 路由 + 多个 AI Service，框架没有“整轮”事件 | 根 `agent` 节点；Sessions 页 |
| UI 确认扣款 | 本项目手工 1 处（`PaymentController`） | 是，约 10 行（另有可选的“把 Trace ID 回传给 UI”辅助方法） | 不是 LangChain4j Tool 调用，没有框架事件 | 独立 Trace 中的 `tool payment.confirm`，与聊天同一 Session |
| 拦截（DENY/STEER/失败策略） | 本项目 Control Adapter | 是，在业务关口 | 必须在动作发生**之前**执行；监听器是事后通知 | 应用回答、HTTP 403/409/503、数据库无草稿/未扣款 |
| Controls 标签 | 本项目 control span + 同一个 Java Agent 导出 | 否（Control 层自动生成） | 评估接口本身不入库 | 被检查步骤下的盾牌节点、Controls 标签 |

## 7. 下一步

- 只看 Observe 的效果：本页第 3 节。
- 理解每一层的代码和配置：[`JAVA-INSTRUMENTATION.md`](JAVA-INSTRUMENTATION.md)。
- 完整演示故事线：[`../demo/DEMO-GUIDE.md`](../demo/DEMO-GUIDE.md)。
- 为什么选这条路线、放弃了哪些方案：[`ARCHITECTURE.md`](ARCHITECTURE.md) 第 6 节。
