# OTel 语义映射：Span 从哪里来、带什么属性、AO 中显示为什么

GenAI 属性名与官方 `splunk-ao-python` SDK（`f7a20ed`，`converter/attribute_mapping.py`）在 o11y 模式下发送的 OTLP 属性一致。“AO 中显示为”一列来自 **2026-11-09 对重构后实现的在线回读**（`GET /ao/api/projects/{id}/traces/{uuid}`，见 [`VALIDATION.md`](VALIDATION.md)），不是推测。常量在 `observability/GenAiAttributes.java`。

## 1. Span 来源总表

| Span | 来源 | 创建位置 | AO 类型 |
|---|---|---|---|
| `POST /api/chat`、`POST /api/payments/drafts/{draftId}/confirm` 等 | **自动生成**（OTel Java Agent，Tomcat / Spring Web MVC） | — | `http` |
| `invoke_agent AccountAgent` / `TransactionsAgent` / `PaymentsAgent` | **框架监听适配**（LangChain4j AI Service 监听） | `LangChain4jOtelBridge` | `agent` |
| `chat <model>` | **框架监听适配**（LangChain4j `ChatModelListener`） | `LangChain4jOtelBridge` | `llm` |
| `execute_tool <工具名>` | **框架监听适配**（`beforeToolExecution` / `afterToolExecution`） | `LangChain4jOtelBridge` | `tool` |
| `invoke_agent BankingAssistant` | **极少自定义**（一轮对话的根） | `ChatService` → `GenAiSpans.startAgent` | `agent` |
| `execute_tool payment.confirm` | **极少自定义**（UI 确认扣款） | `PaymentController` → `GenAiSpans.startTool` | `tool` |
| `<规则名>`（control span） | **极少自定义**（AO 语义适配，仅 Control 开启时） | `ControlEventReporter` | `control` |
| `SELECT …` 等 SQL | 自动生成（Java Agent JDBC），**默认关闭** | — | `workflow`（开启时） |

## 2. 父子关系（一轮聊天，Observe + Control）

```text
http   POST /api/chat                                   ← Java Agent
└─ agent  invoke_agent BankingAssistant                 ← ChatService
   ├─ control  <banking-assistant PRE 规则>              ← 挂在根下
   ├─ llm    chat gpt-5-nano（路由）                      ← supervisor-router 的 control 挂在这里
   ├─ agent  invoke_agent AccountAgent
   │  ├─ llm   chat gpt-5-nano                          ← account-agent 的 PRE/POST control 挂在这里
   │  ├─ tool  execute_tool getAccountBalance
   │  │  └─ control  bank-java-observe-tool-usage（pre） ← Tool 的 control 挂在 Tool 下
   │  └─ llm   chat gpt-5-nano
   ├─ control  <banking-assistant POST 规则>
   └─ llm    chat gpt-5-nano（STEER 后的改写，如有）
```

- 根节点是 Java Agent 的 HTTP Span，AO 的 Trace 名称和 Trace 级 Input / Output 取自它（`POST /api/chat` / `200`）；对话内容在下一层的 `BankingAssistant`。
- `SupervisorAgent` 不再是单独一层：它的路由结果写在根 Span 上（`bank.intent`、`bank.selected_agents`）。
- 确认按钮是另一条 Trace：`http POST /api/payments/drafts/{draftId}/confirm` → `tool execute_tool payment.confirm` → `control bank-java-deny-large-confirm`。

## 3. 各类 Span 的属性

### 3.1 HTTP（自动生成）

由 Java Agent 按 OTel HTTP 语义约定填写：`http.request.method`、`url.path`、`http.route`、`http.response.status_code`、`server.address`、`client.address`、`user_agent.original` 等。AO 中 Input = `POST /api/chat`、Output = 状态码，其余在 metadata。不采集请求/响应正文和请求头。注意 `url.path` 中含草稿 ID（随机 UUID，非个人信息）；`http.route` 是模板 `/api/payments/drafts/{draftId}/confirm`。

### 3.2 Agent —— `invoke_agent <Name>`

| 属性 | 值 | AO 中显示为 |
|---|---|---|
| `gen_ai.operation.name` | `invoke_agent` | `type=agent` |
| `gen_ai.agent.name` | `BankingAssistant`、`AccountAgent`…（领域 Agent 取 AI Service 接口名） | 名称 |
| `gen_ai.provider.name` | `openai` | metadata |
| `gen_ai.conversation.id` | 会话 UUID | 决定 **Session** |
| `gen_ai.input.messages` / `gen_ai.output.messages` | 默认占位符 `[omitted: DEMO_TRACE_CONTENT=false]` | Input / Output |
| `bank.intent`、`bank.selected_agents`（仅根） | 路由结果 | metadata |

### 3.3 LLM —— `chat <model>`

| 属性 | 值 | AO 中显示为 |
|---|---|---|
| `gen_ai.operation.name` | `chat` | `type=llm` |
| `gen_ai.provider.name` | `openai`（LangChain4j `ModelProvider.OPEN_AI` 映射为语义约定值） | provider |
| `gen_ai.request.model` / `gen_ai.response.model` | `gpt-5-nano` / `gpt-5-nano-2025-08-07` | model |
| `gen_ai.request.max_tokens` | 请求参数中的 `maxOutputTokens`（设置了才写） | metadata |
| `gen_ai.response.id`、`gen_ai.response.finish_reasons` | OpenAI 响应 ID、`stop` / `tool_execution` | metadata |
| `gen_ai.usage.input_tokens` / `gen_ai.usage.output_tokens` | **OpenAI 实际返回**的 token；没有返回时不写（不估算） | `num_input_tokens` / `num_output_tokens`，并计算 cost；Trace 级汇总 |
| `gen_ai.conversation.id` | 由父 Agent / 根 Span 经 OTel Context 传入 | Session |
| `gen_ai.input.messages` / `gen_ai.output.messages` | 默认每条消息内容为占位符；`DEMO_TRACE_CONTENT=true` 时为过滤后的消息（含 tool_call） | Input（消息列表）/ Output |
| `bank.control.steer_retries`（仅 Control 开启且发生 POST STEER） | `1`、`2` | metadata；此时 token 为本步骤所有往返之和 |

### 3.4 Tool —— `execute_tool <toolName>`

| 属性 | 值 | AO 中显示为 |
|---|---|---|
| `gen_ai.operation.name` | `execute_tool` | `type=tool` |
| `gen_ai.tool.name`、`gen_ai.tool.call.id` | 工具名、模型给出的调用 ID | 名称、metadata |
| `gen_ai.tool.call.arguments` | 默认 `{"captured":false,"argumentNames":[…]}`（只有参数名） | Input |
| `gen_ai.tool.call.result` | 默认 `{"captured":false,"resultChars":N}` | Output |
| `gen_ai.conversation.id` | AI Service 的 memory ID（= 会话 ID） | Session |
| `bank.control.pre.verdict` / `.latency_ms` / `.control` / `.failure`，`bank.control.post.*` | Control 结果摘要（Control 开启时） | metadata（**不会**出现在 Controls 标签） |
| `bank.control.blocked` | `CONTROL_DENIED`、`CONTROL_STEER`、`CONTROL_UNAVAILABLE`、`CONTROL_DENIED_OUTPUT`… | metadata |

### 3.5 UI 确认 —— `execute_tool payment.confirm`

| 属性 | 值 |
|---|---|
| `gen_ai.operation.name` / `gen_ai.tool.name` | `execute_tool` / `payment.confirm` |
| `gen_ai.conversation.id` | 与创建草稿的聊天相同 → 同一 AO Session |
| `gen_ai.tool.call.arguments` | 默认只有参数名 `draftId` |
| `bank.draft.ref` | 草稿 ID 的不可逆短哈希 |
| `bank.outcome` | `EXECUTED`、`REPLAYED`、`CONTROL_DENIED`、`DRAFT_CANCELLED` 等 |

HTTP 响应头 `X-Bank-Trace-Id` / `X-Bank-AO-Trace-Id`（仅 Observe 生效时）把一次点击与 AO Trace 对应起来，UI 草稿卡片显示该 ID。旧实现中指向草稿创建 Span 的 OTel link 已删除：AO 的 Trace / Span 记录没有 link 字段（2026-11-09 检查 API 返回的全部字段），关联靠同一 Session 与 `bank.draft.ref`。

### 3.6 Control —— `<规则名>`

每个评估过的规则一个，父 Span 为当前被检查的步骤；开始时间按服务端返回的 `execution_duration_ms` 回推。

| 属性 | 值 | AO 中显示为 |
|---|---|---|
| `galileo.span.kind` | `control` | `type=control`（盾牌图标） |
| `gen_ai.operation.name` | `control` | — |
| `agent_control.control_id` / `control_name` / `agent_name` | `18` / 规则名 / `bank-agent-java` | Controls 标签：控件名、View control |
| `agent_control.check_stage` | `pre` / `post` | Stage |
| `agent_control.applies_to` | `tool_call` / `llm_call` | Applies To |
| `agent_control.evaluator_name` / `selector_path` | `list` / `input.beneficiary` | Evaluators、Trigger if |
| `agent_control.action` / `matched` / `confidence` | `deny` / `true` / `1.0` | Triggered（matched=true）；Output |
| `agent_control.control_execution_id` | 服务端执行 ID | metadata |
| `agent_control.error_message` | evaluator 出错时 | Span 状态 ERROR |
| `gen_ai.output.messages` | `{"action","matched","confidence"}` | Output |
| `gen_ai.input.messages` | 默认占位符 | **Input 为空**（AO 不映射 control span 的 Input，K3） |

## 4. Resource 与路由

| 位置 | 值 | 作用 |
|---|---|---|
| Resource `service.name` | `bank-agent-java`（`OTEL_SERVICE_NAME`） | 标准 OTel |
| Resource `deployment.environment.name` | `local-demo` | 标准 OTel |
| Resource `splunk_ao.project.name` / `splunk_ao.logstream.name`（o11y）；`galileo.*`（galileo，未在线验证） | `bank-agent-java` / `bank-java-demo` | 路由（与官方 SDK 相同） |
| 请求头 `project` / `logstream` | 同上 | 路由（必须用 `logstream`；`agentstream` 会进入 “Unassigned”） |
| 请求头 `X-SF-Token` | O11y Access Token | 认证 |
| Resource `telemetry.sdk.*`、`telemetry.distro.*`、`process.*`、`host.*`、`os.*` | Java Agent 自动填写 | 标准 OTel（AO 中为 metadata） |

以上都通过 `OTEL_EXPORTER_OTLP_TRACES_HEADERS` / `OTEL_RESOURCE_ATTRIBUTES` 交给 Java Agent（推导见 [`../CONFIG-MAPPING.md`](../CONFIG-MAPPING.md)）。

## 5. Token、Session 与 ID

- **Token**：只来自 LangChain4j `ChatResponse.tokenUsage()`（即 OpenAI 的 `usage`）。没有返回就不写，不估算。STEER 重试在同一个 LLM Span 内，记录各轮之和。
- **Session**：AO 根据 `gen_ai.conversation.id` 确定性生成。所有 GenAI Span 都带它（根 / 确认由手写代码设置；领域 Agent 与 Tool 来自 LangChain4j 的 memory ID；LLM 来自父 Span 经 OTel Context 传入的值，不进入 Baggage、不随出站请求传播）。在线验证：同一会话的两轮聊天与确认 Trace 位于同一 Session。

| 位置 | 形式 | 例子 |
|---|---|---|
| OTel / 应用响应 `traceId` / Console External Id | 32 位十六进制 | `99dee3334fdf1597b9a9bfb78ed31ad1` |
| AO Trace UUID | 第 13 位改为 `4`、第 17 位改为 `8`–`b` 的 UUID（`AoIds`） | `99dee333-4fdf-4597-b9a9-bfb78ed31ad1` |
| AO Session | 由 `gen_ai.conversation.id` 生成，与会话 ID 不相同 | `8bc5c3e4-…` |

## 6. 默认的敏感信息策略

| 内容 | 默认 | 机制 |
|---|---|---|
| 用户消息、回答、System Prompt、模型消息 | 占位符 | `TraceContentPolicy`（适配类与手写 Span 共用）；`--trace-content` 时导出过滤后的虚构文本（遮蔽密钥与 8 位以上数字，截断） |
| Tool 参数 / 结果 | 只导出参数名 / 结果长度 | 同上 |
| 异常 | `error.type` + 遮蔽后的短消息，**无堆栈** | `GenAiSpans.recordFailure` 不调用 `recordException` |
| HTTP 请求正文、请求头 | 不采集 | Java Agent 默认 |
| SQL | 不采集（JDBC 关闭）；开启时参数被替换为 `?` | `config/otel-javaagent.properties` |
| AO / OpenAI 凭据 | 不在任何 Span、日志或输出中 | Token 只进入 Java Agent 的请求头；黑盒测试与在线日志扫描均为 0 命中 |

## 7. 重复 Span 的抑制

| 可能的重复 | 处理 |
|---|---|
| OpenAI 调用：LLM Span + Agent 的 HTTP 客户端 Span | 关闭 `java-http-client` |
| 一次查询：Repository / Hibernate / JDBC 三层 | 关闭 `spring-data`、`hibernate`（JDBC 默认也关闭） |
| Control 包装器 + 原模型都触发监听器 | `GovernedChatModel.listeners()` 交出原模型的监听器、内部调用 `doChat`：每个受控模型步骤只有一个 LLM Span |
| Control 包装器 + Tool 钩子都建 Tool Span | 包装器不建 Span，只在钩子已开启的 Span 内做 PRE/POST |
| 应用 SDK + Agent SDK 双导出 | 应用不含 SDK（只有 API） |

## 8. 没有填写的字段

- `gen_ai.system_instructions`、`gen_ai.tool.definitions`、缓存 token（`gen_ai.usage.cache_*`）、推理 token：LangChain4j 1.23.0 的通用 `TokenUsage` 没有可靠来源，不填。
- `gen_ai.agent.id`、`gen_ai.agent.description`：本项目的 Agent 没有稳定 ID，不填。
- `splunk_ao.status_code`：已删除。旧验收（K2）证明 AO 只把它当 metadata、记录的 `status_code` 仍为 0；失败用标准的 OTel 状态 `ERROR` + `error.type` 表达。

## 9. 错误表达

| 情况 | Span 上的表达 |
|---|---|
| 业务或 LLM 异常 | OTel 状态 `ERROR`、`error.type=<错误码或异常类名>`、`exception` 事件（type + 遮蔽后的 message，无堆栈） |
| Tool 返回错误结果（含被 Control 拦截） | Tool Span 状态 `ERROR`、`error.type=tool_error`；被拦截时另有 `bank.control.blocked=<代码>` 和 control span |
| OTLP 导出失败 | 不影响业务；Java Agent 在日志中警告 |
