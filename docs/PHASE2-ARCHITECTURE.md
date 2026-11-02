# Phase 2 架构：Observe 与 Agent Control

Phase 2 在 Phase 1 的业务代码外加了两条**相互独立**的数据路径。两个开关可以任意组合，关闭时行为与 Phase 1 完全一致。

| 路径 | 开关 | 方向 | 是否影响业务结果 |
|---|---|---|---|
| **Observe**（OpenTelemetry） | `OBSERVABILITY_ENABLED` | Java → OTLP/HTTP → Splunk AO（异步、批量） | **否**：导出失败只记录日志和计数 |
| **Control**（Agent Control） | `CONTROL_MODE=remote-api` | Java ⇄ Agent Control REST（同步，在请求路径上） | **是**：DENY / STEER / fail-closed 会阻止或改写步骤 |

## 1. 总体结构

```mermaid
flowchart TD
    B["浏览器 / curl"] -->|"/api/chat"| CS["ChatService<br/>根 Span + banking-assistant PRE/POST"]
    B -->|"确认 / 取消"| PC["PaymentController<br/>payment.confirm 根 Span + PRE 门禁"]
    CS --> SUP["SupervisorAgent<br/>invoke_agent SupervisorAgent"]
    SUP --> RT["LlmIntentRouter"]
    SUP --> GW["LangChainDomainAgentGateway<br/>invoke_agent &lt;X&gt;Agent"]
    GW --> AIS["LangChain4j AiServices"]
    RT --> GCM["GovernedChatModel<br/>chat span + LLM Control"]
    AIS --> GCM
    AIS --> GTE["GovernedToolExecutor<br/>execute_tool span + Tool Control"]
    GCM --> OAI(("OpenAI"))
    GTE --> TOOLS["@Tool 方法（未修改）"] --> SVC["Service / H2"]
    PC --> SVC

    subgraph Control["Control 路径（同步）"]
        RUN["ControlledStepRunner"] --> GWC["ControlGateway<br/>Remote / NoOp"]
        RUN --> REP["ControlEventReporter"]
    end
    CS --> RUN
    PC --> RUN
    GCM --> RUN
    GTE --> RUN
    GWC -->|"HTTPS /api/v1/evaluation"| AC(("Agent Control"))

    subgraph Observe["Observe 路径（异步）"]
        TEL["BankTelemetry<br/>Tracer / BatchSpanProcessor"] --> EXP["OTLP/HTTP Exporter"]
    end
    REP -->|control span| TEL
    CS -.Span.-> TEL
    SUP -.Span.-> TEL
    GW -.Span.-> TEL
    GCM -.Span.-> TEL
    GTE -.Span.-> TEL
    PC -.Span.-> TEL
    EXP -->|"/v2/trace/otlp"| AO(("Splunk AO"))
```

设计要点：

- **业务代码基本不动**：`@Tool` 方法、Service、数据库逻辑没有修改；模型和工具通过装饰器（`GovernedChatModel`、`GovernedToolExecutor`）接入，Agent 与根节点只增加了 Span 包装。
- **一个执行点同时负责 Span 和 Control**：Span 在步骤开始前创建，PRE 检查在 Span 内执行，因此 control span 自然成为被检查步骤的子 Span。
- **规则不写在 Java 里**：Java 只知道“步骤名、输入、输出”，决策全部来自服务端。

## 2. 新增的包与类

| 包 | 主要类 | 职责 |
|---|---|---|
| `observability` | `AoRawProperties` → `AoEndpointResolver` → `AoSettings` | 读取 `galileo.properties`，按配置档推导全部地址、认证头与默认值，校验并在启动日志中显示来源（`[derived]` / `[override]` / `[default]`），凭据只显示 set/missing |
| | `AoApiClient`、`AoTargetResolver`、`AoTargetHolder` | 启动时按名称查找或创建 Project / Agent Stream，保存 UUID |
| | `OtelConfiguration`、`BankTelemetry` | 创建 OTel SDK（或 no-op），导出统计 |
| | `AgentSpanRunner`、`GenAiAttributes`、`TraceContentPolicy`、`AoIds`、`DraftTraceRegistry` | Agent Span 写法、属性名、内容过滤、ID 转换、草稿 → 创建 Span 的 link |
| `control` | `ControlGateway`、`RemoteAgentControlGateway`、`NoOpControlGateway` | Agent Control 的 HTTP 客户端（注册、Runtime Token、规则缓存、评估、响应映射） |
| | `ControlledStepRunner`、`ControlOutcome`、`ControlDecision`、`ControlFailureMode` | 决策 → Verdict，failure mode |
| | `ControlEventReporter` | control span（Console Controls 标签）+ UI 执行详情 |
| | `StepCatalog`、`ControlStep`、`StepAccess`、`ControlExceptions` | 步骤目录、读/写属性、Java 异常 |
| `governance` | `StepGovernance`、`GovernedChatModel`、`GovernedToolExecutor` | 包装模型与工具 |
| `tool` | `TransferToolSideEffects` | 写 Tool 输出被拦截时撤回草稿 |
| `cli` | `Phase2Cli` | `scripts/preflight.sh`、`scripts/phase2-smoke.sh` 使用的命令行工具（复用同一个推导器，不启动 Spring） |

修改的 Phase 1 类：`ChatService`、`SupervisorAgent`、`LangChainDomainAgentGateway`、`LlmIntentRouter`、`AgentConfig`、`PaymentController`、`PaymentService`（只新增方法）、`StatusController`、`UiController`、`ExecutionTrace`、`ErrorCode`、`DemoResetService`，以及前端 `app.js` / `index.html` / `app.css`。

## 3. 启动顺序

```mermaid
sequenceDiagram
    autonumber
    participant App as Spring Boot 启动
    participant Res as AoEndpointResolver
    participant API as AO REST API
    participant OT as OtelConfiguration
    participant GW as RemoteAgentControlGateway
    participant AC as Agent Control
    App->>Res: 读取 galileo.properties + 环境变量
    Res-->>App: AoSettings（错误配置 → 启动失败，不降级）
    alt Observe 或 Control 开启
        App->>API: 按名称查找 Project / Agent Stream，不存在则创建
        API-->>App: UUID（仅内存）
    end
    opt OBSERVABILITY_ENABLED=true
        App->>OT: 创建 OTLP Exporter + BatchSpanProcessor
    end
    opt CONTROL_MODE=remote-api
        GW->>AC: GET /health
        GW->>AC: POST initAgent（步骤目录、target=Agent Stream UUID）
        GW->>AC: POST runtime-token-exchange
        GW->>AC: GET 已绑定规则
        Note over GW: 任何一步失败 → 启动失败（不会悄悄变成 off）
    end
```

## 4. 一轮聊天（Observe + Control 都开启）

```mermaid
sequenceDiagram
    autonumber
    actor U as 用户
    participant CS as ChatService
    participant R as ControlledStepRunner
    participant AC as Agent Control
    participant SUP as Supervisor / Agent
    participant M as GovernedChatModel
    participant T as GovernedToolExecutor
    participant DB as H2
    U->>CS: POST /api/chat
    Note over CS: 根 Span invoke_agent BankingAssistant
    CS->>R: PRE banking-assistant（用户消息）
    R->>AC: evaluation
    AC-->>R: 决策（DENY → 直接返回拦截提示，不调用模型）
    CS->>SUP: handle（invoke_agent SupervisorAgent）
    SUP->>M: 路由 / Agent 的模型调用（chat span，PRE/POST）
    M-->>SUP: 工具调用请求
    SUP->>T: execute_tool createTransferDraft
    T->>R: PRE（参数）
    R->>AC: evaluation
    alt DENY / STEER / fail-closed
        T-->>SUP: 错误 JSON（方法未执行，无草稿）
    else 放行
        T->>DB: 真正的 @Tool 方法
        T->>R: POST（结果）
        Note over T: POST 拦截 → 结果不交给模型；写 Tool 撤回草稿
    end
    SUP-->>CS: 回答
    CS->>R: POST banking-assistant（最终回答）
    Note over CS: STEER → 纯文本改写（有限次数，每次重新检查）
    CS-->>U: answer + controlDecisions + traceId / aoTraceId
    Note over CS: 所有 Span 结束后由 BatchSpanProcessor 异步发往 AO
```

点击“确认”是另一条独立的 Trace：`payment.confirm` 根 Span → PRE 检查（扣款前）→ `PaymentService.confirm` → POST（仅审计）。它通过 Span link 指向创建草稿的 Tool Span，并与聊天位于同一 AO Session。

## 5. 开关组合

| `OBSERVABILITY_ENABLED` | `CONTROL_MODE` | 行为 | 验证 |
|---|---|---|---|
| `false` | `off` | 与 Phase 1 完全一致：不查找后端、不创建 Span、不发 Control 请求 | `ControlDisabledRegressionIT`、Phase 1 全部测试 |
| `true` | `off` | 只有 Trace；没有 control span；规则不生效 | 在线 O-A/O-B/O-C、C-G |
| `false` | `remote-api` | 规则生效；决策显示在 UI 执行详情中；Console 中没有 Trace | 离线测试；在线未单独验证 |
| `true` | `remote-api` | Trace + 规则 + Controls 标签 | 在线 C-A…C-F、P1 回归 |

`scripts/run.sh --observe` / `--control` / `--trace-content` / `--phase2-off` 只对本次启动的 JVM 设置环境变量，不修改私有配置文件。

## 6. 线程、上下文与隔离

- 一轮聊天在一个 HTTP 工作线程中同步执行（LangChain4j 的工具调用也在同一线程），因此 OTel Context 和 Phase 1 的 `ExecutionTrace`（ThreadLocal）都能沿调用链传递，无需手动跨线程传播。
- Span 导出由 `BatchSpanProcessor` 在后台线程完成，不阻塞请求；队列上限 2048，超出时丢弃 Span 而不是阻塞业务。
- Agent Control 评估在请求线程上同步执行（必须在步骤前得到结果）：典型延迟约 0.26 秒/次；对没有绑定规则的步骤不发请求（规则列表缓存，最多每 5 秒刷新）。
- 应用不把 OTel SDK 注册为全局实例，也让 Spring Boot 自带的 OTel 自动配置退出，避免重复导出。

## 7. 相关文档

- [`PHASE2-JAVA-INSTRUMENTATION.md`](PHASE2-JAVA-INSTRUMENTATION.md)：插桩教程与修改前后代码
- [`PHASE2-CONTROL-API.md`](PHASE2-CONTROL-API.md)：Control 契约、决策映射、各执行点处理
- [`PHASE2-OTEL-SEMANTIC-MAPPING.md`](PHASE2-OTEL-SEMANTIC-MAPPING.md)：Span 属性与 AO 字段
- [`PHASE2-PROTOCOL-VERIFICATION.md`](PHASE2-PROTOCOL-VERIFICATION.md)：P0 协议验证
- [`PHASE2-VALIDATION.md`](PHASE2-VALIDATION.md)：验收结果与已知问题
- [`CONFIG-MAPPING.md`](CONFIG-MAPPING.md)：配置键与推导规则
