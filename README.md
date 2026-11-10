# 银行 AI 助理（Java）+ Galileo 可观测与运行时管控 — macOS 本地演示

> **DEMO ACCOUNT · Demo / Simulated Banking**：本项目中的客户、账户、交易与收款人全部为虚构数据，所有"转账"只是本地 H2 数据库中的模拟记账，不连接任何真实银行系统。

很多企业级 AI Agent 都用 Java 开发，特别是金融领域。本项目向这类客户演示：**一个现有的 Java Agent，只需很少的改动，就能充分使用 Splunk Agent Observability（AO，Galileo）的观测与管控能力**。`bank-agent-java` 是在 macOS 本机运行的 Java 多 Agent 银行助理（Spring Boot + LangChain4j + OpenAI），接入了 Splunk Observability Cloud 中的 Splunk AO SaaS。

> **观测自动优先，管控只在业务关口定制**
>
> - **Observe**：启动时挂载 OpenTelemetry（OTel）官方 Java Agent（零代码），再用 LangChain4j 框架自带的监听机制（注册一次）得到 Agent → LLM → Tool 调用链、真实 token 与 Session；业务代码中只有 2 处手写 Span。
> - **Control**：Console 中定义的规则由 Agent Control 服务返回决策，Java 在真正需要拦住的业务关口（Tool 执行前后、模型调用前后、确认扣款前）执行 DENY / STEER / OBSERVE；这部分是本项目写的代码，不是自动插桩。
> - 两者独立开关；都关闭时应用与没有插桩时完全一致。5 分钟版本见 [`docs/galileo/QUICKSTART.md`](docs/galileo/QUICKSTART.md)。

内容分为三部分：

| 部分 | 内容 | README 章节 | 详细文档 |
|---|---|---|---|
| 银行 AI 应用 | 多 Agent 架构、OpenAI Tool Calling、模拟银行与人工确认转账、安装运行、API、测试 | 第 1–9 节 | [`docs/app/VALIDATION.md`](docs/app/VALIDATION.md)、[`docs/CONFIG-MAPPING.md`](docs/CONFIG-MAPPING.md) |
| Galileo 插桩 | Observe（OTel 官方 Java Agent + LangChain4j 监听）与 Agent Control 门禁，两者可独立开关 | 第 10–13 节 | [`docs/galileo/`](docs/galileo/)（从 [`QUICKSTART.md`](docs/galileo/QUICKSTART.md) 开始） |
| 演示 | 把应用与 Galileo 能力串成一条完整的故事线：操作步骤、预计现象、准备与复位 | 第 14 节 | [`docs/demo/DEMO-GUIDE.md`](docs/demo/DEMO-GUIDE.md) |
| 附录 | 常见问题、安全与限制、参考资料 | 第 15–17 节 | — |

## 快速开始

```bash
cd /Users/hangwe/Library/CloudStorage/OneDrive-Cisco/dev/bank-agent-java
scripts/preflight.sh                               # 检查 Java 21、私有配置、Galileo 推导结果与 OTel Java Agent（只读，不打印密钥）
scripts/run.sh --background                        # 只运行银行 AI 应用（不观测、不门禁）
scripts/run.sh --background --observe              # Observe：挂载 OTel 官方 Java Agent，Trace 发往 Splunk AO
scripts/run.sh --background --control              # 只开 Control：规则生效，不导出 Trace
scripts/run.sh --background --observe --control    # 完整演示（需先配置 galileo.properties，见第 6.2 节）
scripts/run.sh --galileo-off                       # 临时全部关闭（覆盖私有文件中的开关）
open http://127.0.0.1:8080/
scripts/stop.sh                                    # 切换模式前先停止
```

## 第一部分：银行 AI 应用

### 1. 项目目标与适用场景

`bank-agent-java` 是一个在 macOS 本机单进程运行的**多 Agent 银行助理**，用于：

- 演示一个真实可运行的 Java AI Agent：Supervisor 通过 OpenAI 模型理解意图并动态路由，三个领域 Agent（Account / Transactions / Payments）通过 LangChain4j **真实 Tool Calling** 调用 Java 业务服务读取/写入 H2 模拟银行数据库；
- 演示金融场景中"LLM 只能准备、人工点击才能执行"的安全模式：转账先生成草稿，只有用户在 UI 上点击 **确认** 才由后端执行；
- 演示 Galileo 插桩（第二部分）：Observe 以 OTel 官方 Java Agent + LangChain4j 监听为主、几乎不改业务代码；Agent Control 门禁只加在业务关口——Console 中定义的规则在 Java 中真实执行（PRE/POST、DENY/STEER/OBSERVE、失败模式）。两个开关默认关闭，关闭时应用的行为与没有插桩时完全一致。

适用场景：本机技术演示、Agent 架构与可观测/治理讲解。**不适用于生产环境**（见第 16 节）。

### 2. 技术栈与运行环境

| 类别 | 实际采用 | 说明 |
|---|---|---|
| 操作系统 | macOS（Apple Silicon 实测） | 仅本机 `127.0.0.1` 访问 |
| JDK | Java 21（本机已有 Temurin 21，SDKMAN 安装） | 脚本仅在**自身进程内**选用 Java 21，不修改系统默认 JDK |
| 构建 | Maven Wrapper（Maven 3.9.16） | 无需预装 Maven；首次构建会下载依赖到 `~/.m2` |
| 应用框架 | Spring Boot 3.5.16（Spring Framework 6.2.19） | Web MVC、Data JPA、Validation、Thymeleaf、Actuator |
| AI 框架 | LangChain4j 1.23.0（`langchain4j`、`langchain4j-open-ai`） | AiServices、`@Tool`、ChatMemory、JSON Schema 结构化输出 |
| LLM | OpenAI 官方 API，默认模型 **`gpt-5-nano`**（实测返回 `gpt-5-nano-2025-08-07`） | 低成本默认值；可通过私有配置切换 |
| 数据库 | H2 2.3.232（文件模式） | 默认位于 `~/.local/share/bank-agent-java/h2/` |
| 前端 | Thymeleaf 单页 + 原生 JS/CSS | 无 Node / npm 依赖 |
| Galileo 插桩：观测 | **OTel 官方 Java Agent 2.33.1**（`-javaagent`，版本与 SHA-256 固定）+ LangChain4j 官方监听（`ChatModelListener`、AI Service 监听、Tool 前后钩子）；应用只依赖 `opentelemetry-api` | `scripts/run.sh --observe`；默认后端 Splunk AO SaaS（Splunk Observability Cloud） |
| Galileo 插桩：运行时管控 | Agent Control REST Adapter（JDK `java.net.http.HttpClient`，Server Execution） | `scripts/run.sh --control`（`CONTROL_MODE=off\|remote-api`），与观测相互独立 |
| 测试 | JUnit 5、AssertJ、Spring Boot Test、MockMvc、OTel `InMemorySpanExporter`、**真实 `-javaagent` 黑盒测试**（本地 OTLP 接收器） | 全部离线；集成测试使用脚本化的 Stub 模型和 Mock Control |

运行时的外部网络依赖是 OpenAI API；开启 Galileo 插桩后还会访问 Splunk AO（OTLP 端点、REST API、Agent Control），构建时以及首次 `--observe`（下载 OTel Java Agent）时需要 Maven Central。不需要 Docker、Python、Node、Collector 或 Kubernetes。

### 3. 系统整体架构

```mermaid
flowchart TD
    B["macOS 浏览器<br/>http://127.0.0.1:8080/"] -->|"POST /api/chat"| CC["ChatController"]
    B -->|"点击 确认 / 取消<br/>POST /api/payments/drafts/{id}/confirm 或 /cancel"| PC["PaymentController"]
    B -->|"POST /api/demo/reset（仅本机）"| DC["DemoController"]
    CC --> CS["ChatService<br/>会话锁 / ExecutionTrace"]
    CS --> CONV["ConversationService<br/>UUID 会话、TTL、历史"]
    CS --> SUP["SupervisorAgent"]
    SUP --> RT["LlmIntentRouter<br/>OpenAI + JSON Schema"]
    SUP --> GW["LangChainDomainAgentGateway"]
    GW --> AA["AccountAgent"]
    GW --> TA["TransactionsAgent"]
    GW --> PA["PaymentsAgent"]
    AA --> AT["AccountTools (@Tool)"]
    TA --> TT["TransactionTools (@Tool)"]
    PA --> PT["PaymentTools (@Tool)<br/>无执行转账工具"]
    AT --> SVC["AccountService / TransactionService / PaymentService"]
    TT --> SVC
    PT --> SVC
    PC --> SVC
    DC --> RS["DemoResetService"]
    SVC --> DB[("H2 文件数据库<br/>账户 / 交易 / 草稿 / 审计")]
    RT -.->|HTTPS| OAI(("OpenAI API"))
    AA -.-> OAI
    TA -.-> OAI
    PA -.-> OAI
    CS -.->|"Span（--observe：OTel Java Agent 导出）"| AO(("Splunk AO<br/>OTLP / Console"))
    CS -.->|"PRE/POST 评估（--control）"| AC(("Agent Control"))
    PC -.-> AC
```

图中虚线是 Galileo 插桩的两条独立路径：**Observe**（OTel Java Agent 异步把 Span 发往 Splunk AO，不影响业务结果）和 **Control**（同步评估，DENY/STEER/不可用时阻止或改写步骤）。

**Galileo 插桩的最终结构**：

```mermaid
flowchart LR
    subgraph APP["银行 AI 应用（业务逻辑不变）"]
        H["HTTP 请求"] --> CS2["ChatService"] --> AIS["LangChain4j AI Services"]
        AIS --> CM["ChatModel → OpenAI"]
        AIS --> T["@Tool → Service / H2"]
    end
    JA["① OTel 官方 Java Agent<br/>HTTP Span · 上下文 · OTLP 导出"]
    BR["② LangChain4j 监听 → GenAI Span<br/>agent / llm / tool（注册一次）"]
    MAN["③ 手写 2 处<br/>对话根 · 确认扣款"]
    CTL["Control Adapter<br/>业务关口 PRE/POST"]
    H -.-> JA
    CM -.-> BR
    AIS -.-> BR
    T -.-> BR
    CS2 -.-> MAN
    CS2 --> CTL
    CM --> CTL
    T --> CTL
    CTL -->|同步 REST| AC2(("Agent Control"))
    CTL -.->|control span| JA
    BR --> JA
    MAN --> JA
    JA -->|"单一 OTLP 通道 /v2/trace/otlp"| AO2(("Splunk AO"))
```

各层的职责、启动与请求时序、选型依据见 [`docs/galileo/ARCHITECTURE.md`](docs/galileo/ARCHITECTURE.md)。

**一次真实请求的端到端时序**（以"向张三转账 500 元"为例）：

```mermaid
sequenceDiagram
    autonumber
    actor U as 用户（浏览器）
    participant API as ChatController / ChatService
    participant SUP as SupervisorAgent
    participant LLM as OpenAI (gpt-5-nano)
    participant PA as PaymentsAgent (LangChain4j AiService)
    participant T as PaymentTools.createTransferDraft
    participant S as PaymentService
    participant DB as H2
    U->>API: POST /api/chat {conversationId?, message}
    API->>SUP: handle(conversationId, message, history)
    SUP->>LLM: 路由请求（JSON Schema 结构化输出）
    LLM-->>SUP: {"intent":"AGENTS","steps":[{"agent":"PAYMENTS",...}]}
    SUP->>PA: chat(conversationId, [用户原话] + [Supervisor 任务])
    PA->>LLM: system prompt + 会话记忆 + 工具定义
    LLM-->>PA: tool_call createTransferDraft(张三, 500)
    PA->>T: 执行 Java @Tool
    T->>T: 金额守卫：500 是否出现在用户原话中？
    T->>S: createDraft(...)：校验收款人/余额/限额
    S->>DB: INSERT transfer_draft (PENDING_CONFIRMATION) + payment_audit
    T-->>PA: JSON 结果（草稿、nextStep）
    PA->>LLM: 工具结果
    LLM-->>PA: 自然语言回复
    PA-->>SUP: 回复
    SUP-->>API: answer + 路由信息
    API-->>U: answer、selectedAgents、executedTools、llmCalls、pendingDraft
    Note over U: 草稿卡片显示 确认 / 取消 按钮（余额此时未变）
    U->>API: 点击确认 → POST /api/payments/drafts/{id}/confirm
    API->>S: confirm()：加锁、重新校验、扣款、写交易与审计（同一事务）
    S->>DB: UPDATE 余额 / INSERT 交易 / UPDATE 草稿 EXECUTED
```

### 4. Agent 结构与工作原理

所有 Agent 共用一个 `ChatModel`（`llm/LlmConfig.java`），每个领域 Agent 都是独立的 LangChain4j `AiServices` 接口，拥有**按 conversationId 隔离的独立记忆**与独立的工具集（`agent/AgentConfig.java`）。

#### 4.1 Supervisor Agent

- 关键代码：`agent/SupervisorAgent.java`、`agent/LlmIntentRouter.java`、`agent/RoutingDecision.java`、`agent/AgentPrompts.java`（`supervisorPrompt`）。
- **路由由 OpenAI 模型完成**，不是关键词匹配：`LlmIntentRouter` 把最近 8 轮会话历史 + 最新用户消息发给模型，并使用 **JSON Schema 严格结构化输出**（`intent ∈ {AGENTS, CLARIFY, OUT_OF_SCOPE, SMALL_TALK}`，`steps[{agent ∈ {ACCOUNT, TRANSACTIONS, PAYMENTS}, task}]`）。
- Java 侧再做确定性校验与兜底：无效 JSON / 未知 Agent / 空任务 → `CLARIFY`（`fallback=true`）；步骤数上限 `max-route-steps=3`；**同一 Agent 只执行第一个步骤**（防止模型重复派发造成重复草稿）。
- 跨领域问题（如"余额和最近五笔交易"）按步骤**顺序委派**，再由 Java 以 **账户信息** / **交易记录** / **转账服务** 分节确定性地合并答复。
- 委派输入的组合方式（防止 LLM 篡改用户意图）：
  - 单 Agent 回合：`[User message - authoritative]` 用户原话 + `[Supervisor note]` 路由任务；
  - 多 Agent 回合：只给每个 Agent `[Your part ...]` 它负责的那部分任务，避免 Agent 越界回答。
- `CLARIFY` / `OUT_OF_SCOPE` / `SMALL_TALK` 不调用任何领域 Agent。

#### 4.2 Account Agent

- 关键代码：`agent/AccountAgent.java`、`tool/AccountTools.java`、`service/AccountService.java`。
- 工具：`getAccountBalance`（活期/储蓄/全部）、`getAccountSummary`（脱敏概况、总余额、收款人数）、`listBeneficiaries`、`listPaymentMethods`。
- 账号一律以 `DEMO-****-0001` 形式脱敏返回。

#### 4.3 Transactions Agent

- 关键代码：`agent/TransactionsAgent.java`、`tool/TransactionTools.java`、`service/TransactionService.java`、`service/Period.java`。
- 工具：`listRecentTransactions`（最近 N 笔，上限 50，按时间倒序）、`searchTransactions`（日期、收/支、类别、商户关键字、金额区间）、`summarizeSpending`（`THIS_MONTH` / `LAST_MONTH` / `LAST_7_DAYS` / `LAST_30_DAYS` / `LAST_90_DAYS` / `CUSTOM`）。
- **所有金额计算由 Java（`BigDecimal`）完成**：分类合计、占比、Top 类别、收入合计；默认不把"转账"计入消费并单独返回被排除的转账金额。LLM 只负责解释结果。

#### 4.4 Payments Agent

- 关键代码：`agent/PaymentsAgent.java`、`tool/PaymentTools.java`、`service/PaymentService.java`、`service/AmountMentions.java`。
- 工具：`listTransferBeneficiaries`、`validateTransfer`、`createTransferDraft`、`cancelPendingTransfer`、`getRecentTransferActivity`（用于解释最近失败/拒绝的原因）。
- **没有任何"执行转账"的 `@Tool`**。`PaymentService.confirm()` 只被 `PaymentController` 在用户点击 UI **确认** 按钮时调用。
- 金额守卫（确定性 Java 校验）：`createTransferDraft` 的金额必须**出现在用户本轮原话中**（支持 `500`、`1,200.50`、`1.5万`、`三百`、`两千五百` 等写法）。模型自行"推断"或编造的金额会被拒绝并写入审计（代码 `AMOUNT_NOT_STATED_BY_USER`），不会生成草稿。

#### 4.5 OpenAI 调用、Tool Calling 与多轮对话

- `OpenAiChatModel`（LangChain4j）直接调用 `https://api.openai.com/v1`；`store=false`；GPT-5 系列不发送 temperature，使用 `reasoning_effort=low`；`max_completion_tokens=4096`；超时 90 s、重试 1 次。
- Tool Calling 由模型决定何时调用：LangChain4j 将 `@Tool` 方法转换为 OpenAI tools 定义，执行 Java 方法后把 JSON 结果回传模型生成最终回答；单回合最多 6 次顺序工具调用（`max-tool-invocations`），防止循环调用产生费用。
- 多轮对话：
  - 每个会话有服务端生成的 UUID；`ConversationService` 保存会话历史（TTL 2 小时、最多 500 个会话），同一会话请求串行处理（并发时返回 `CONVERSATION_BUSY`）。
  - Supervisor 读取最近 8 轮历史；每个领域 Agent 有 `MessageWindowChatMemory`（20 条），以 conversationId 为 key，跨会话完全隔离。
  - UI 中的确认/取消结果会以系统备注写入会话历史，使"刚才那笔转账为什么失败"这类追问可以被正确解释。
  - 未知或已过期的 conversationId 会自动开启新会话（返回 `newConversation=true`）。
- 每次回合的**真实执行记录**（`trace/ExecutionTrace.java` + `llm/TraceChatModelListener.java`）：路由结果、每个 Agent 步骤耗时、每次工具调用（参数经脱敏）、每次 LLM 调用的模型名与 token 用量、草稿事件。这些数据随 `/api/chat` 响应返回，并显示在 UI 的"执行详情"面板中。
- 默认关闭请求/响应全文日志；日志中的 `sk-...` 与 `Bearer ...` 会被 `SecretMasker` 遮蔽。

#### 4.6 模拟银行数据库与人工确认安全机制

数据表（`src/main/resources/db/schema.sql`）：`demo_customer`、`bank_account`、`beneficiary`、`bank_transaction`、`transfer_draft`、`payment_audit`。

转账状态机：

```mermaid
stateDiagram-v2
    [*] --> PENDING_CONFIRMATION: createTransferDraft（校验通过）
    PENDING_CONFIRMATION --> EXECUTED: 用户点击 确认（事务内重新校验并扣款）
    PENDING_CONFIRMATION --> CANCELLED: 用户点击 取消 / 取消工具 / 被同会话新草稿取代
    PENDING_CONFIRMATION --> EXPIRED: 超过 10 分钟
    PENDING_CONFIRMATION --> FAILED: 确认时余额不足等
    EXECUTED --> EXECUTED: 重复确认 = 幂等重放（不重复扣款）
```

安全规则：

- 收款人白名单（仅 3 名已登记收款人）、金额 > 0 且最多 2 位小数、单笔上限 50,000.00 CNY、余额充足、只能从活期账户转出；
- 随机 UUID `draftId`，草稿绑定 conversationId 与客户；跨会话确认/取消返回 `403 DRAFT_CONVERSATION_MISMATCH` 并审计；
- 确认时使用悲观锁（`SELECT ... FOR UPDATE`）并在同一事务中重新校验、扣款、写交易流水（`reference=draftId`）、更新草稿与审计；
- 重复确认 → `replayed=true`，不重复扣款；取消后确认 → `409 DRAFT_CANCELLED`；已执行草稿取消 → `409 DRAFT_ALREADY_EXECUTED`；过期 → `409 DRAFT_EXPIRED`；
- 自然语言"确认"不会执行转账：Payments Agent 没有执行工具，提示词也要求引导用户点击按钮；
- 所有拒绝、取代、过期、确认、重放都写入 `payment_audit`。

### 5. 源代码目录与关键 Java 类

```text
bank-agent-java/
├── README.md                         本文档（总览与导航）
├── pom.xml / mvnw / mvnw.cmd / .mvn/ Maven Wrapper 构建
├── config/                           不含密钥的配置模板
│   ├── application-secrets.example.properties   OpenAI 配置模板
│   ├── galileo.example.properties     Galileo 插桩配置模板（Splunk AO SaaS 只需填 2 项）
│   └── otel-javaagent.properties      OTel 官方 Java Agent 的标准设置（无密钥）
├── docs/
│   ├── CONFIG-MAPPING.md             配置键映射、来源与推导规则（不含值）
│   ├── app/VALIDATION.md             银行 AI 应用验收记录
│   ├── galileo/                      Galileo 插桩：QUICKSTART、架构、Java 插桩教程、Agent Control、语义映射、协议验证、验收记录
│   ├── demo/                         演示指南 DEMO-GUIDE.md 与演示规则定义 agent-control-rules.json
│   └── images/                       Console 截图
├── scripts/                          preflight / run / stop / test / reset-demo / galileo-smoke（lib/common.sh 公共函数）
├── tools/protocol-probes/            协议验证探针（OtlpProbe、ControlProbe、ErrorSpanProbe，仅本地手动运行）
└── src/
    ├── main/java/com/demo/bankagent/
    │   ├── BankAgentApplication.java
    │   ├── api/            ChatController, PaymentController, DemoController, StatusController,
    │   │                   UiController, GlobalExceptionHandler, ApiError, ApiRequests
    │   ├── agent/          SupervisorAgent, LlmIntentRouter, IntentRouter, RoutingDecision, AgentType,
    │   │                   AccountAgent, TransactionsAgent, PaymentsAgent, AgentConfig, AgentPrompts,
    │   │                   LangChainDomainAgentGateway, DomainAgentGateway, AgentMemoryStores, AgentMemoryManager
    │   ├── tool/           AccountTools, TransactionTools, PaymentTools, ToolSupport,
    │   │                   TransferToolSideEffects（写 Tool 输出被 Agent Control 拦截时撤回草稿）
    │   ├── service/        AccountService, TransactionService, PaymentService, DemoDataService,
    │   │                   DemoResetService, AmountParser, AmountMentions, Period, Views, ErrorCode ...
    │   ├── llm/            LlmConfig, TraceChatModelListener, LlmErrorTranslator, SecretMasker, UnconfiguredChatModel
    │   ├── trace/          ExecutionTrace 及 Agent/Tool/LLM/草稿/Control 决策记录（UI 执行详情）
    │   ├── conversation/   ChatService, ConversationService, Conversation
    │   ├── domain/         JPA 实体与枚举
    │   ├── repository/     Spring Data JPA 仓库
    │   ├── config/         BankAgentProperties, ClockConfig
    │   │   —— 以下为 Galileo 插桩 ——
    │   ├── observability/  LangChain4jOtelBridge（唯一的 Observe 适配层：LangChain4j 事件 → GenAI Span）、
    │   │                   GenAiSpans/OtelConfig（使用 Java Agent 提供的 OTel API）、GenAiAttributes、TraceContentPolicy、AoIds、
    │   │                   AoEndpointResolver/AoSettings（配置推导，含 OTEL_* 变量）、AoTargetResolver（查找或创建 Project/Agent Stream）
    │   ├── control/        ControlGateway（Remote/NoOp）、ControlledStepRunner、ControlEventReporter、StepCatalog、
    │   │                   ControlDecision/ControlOutcome、ControlExceptions
    │   ├── governance/     Control 执行点（只在 Control 开启时插入）：StepGovernance、GovernedChatModel、GovernedToolExecutor
    │   └── cli/            GalileoCli（otel-env / preflight / smoke / trace 命令行工具）
    ├── main/resources/     application.yml, db/schema.sql, templates/index.html, static/{css,js}
    └── test/java/...       单元测试（*Test）与集成测试（*IT），support/ 下为测试替身
```

Galileo 插桩利用框架的扩展点，而不是逐个业务类包装：LLM / Agent / Tool Span 来自 `LangChain4jOtelBridge`（在 `LlmConfig`、`AgentConfig` 注册一次），HTTP Span 和导出来自 OTel Java Agent；手写的 Span 只有 `ChatService`（一轮对话的根）和 `PaymentController`（确认扣款）两处。`SupervisorAgent`、`LangChainDomainAgentGateway`、`@Tool` 方法和 Service 中没有插桩代码。Control 的门禁包装只在 `--control` 时插入。逐项说明见 [`docs/galileo/JAVA-INSTRUMENTATION.md`](docs/galileo/JAVA-INSTRUMENTATION.md)。

### 6. macOS 安装与本地配置

#### 6.1 JDK 21 与 Maven Wrapper

本机已有 Java 21（Temurin 21，由 SDKMAN 安装在 `~/.sdkman/candidates/java/21-tem`）。注意：在本机上 `/usr/libexec/java_home -v 21` **找不到**该 JDK（它不在 `/Library/Java/JavaVirtualMachines`），因此脚本按以下顺序选择 Java 21，且只 `export` 到**脚本自身进程**：

1. 当前 `JAVA_HOME`（若为 21）；
2. `/usr/libexec/java_home -v 21`；
3. `~/.sdkman/candidates/java/21*`；
4. `PATH` 中的 `java`（若为 21）。

脚本不会执行 `sdk default`、不会修改 `~/.zshrc`、不会安装或替换任何 JDK，对本机其他 Java 应用没有影响。Maven 由 Wrapper 自动下载（`./mvnw`），无需安装。

```bash
cd /Users/hangwe/Library/CloudStorage/OneDrive-Cisco/dev/bank-agent-java
java -version            # 确认仍为 21
scripts/preflight.sh     # 检查 macOS、Java 21、Wrapper、端口、私有配置是否就绪（不打印密钥）
```

#### 6.2 安全复用 Golden Demo Credentials

- 只读审阅了 `splunk-ao-golden-demo` 的 `.streamlit/secrets.toml`、`domains/bank/config.yaml` 和 `setup_env.py`，**未修改原项目**。
- 原项目 `openai_api_key` 属于 **OpenAI 官方 API**（已用真实调用验证），被复制到本机私有文件。Galileo 插桩的后端默认为 Splunk Observability Cloud 集成的 Splunk AO SaaS，其配置不从 Golden Demo 复制；如需改用 Golden Demo 所用的 Galileo 多租户云或传统的 `app.galileo.ai`，按 `galileo.properties` 第 1 组注释切换即可（见第 11 节与 `docs/CONFIG-MAPPING.md`）：

  ```text
  ~/.config/bank-agent-java/application-secrets.properties   (目录 0700，文件 0600，OpenAI 配置)
  ~/.config/bank-agent-java/galileo.properties               (0600，Galileo 插桩配置：观测后端 / Agent Control)
  ```

- 两个文件都在仓库与 OneDrive 同步目录**之外**。应用通过 `spring.config.import` 读取两个文件（`BANK_AGENT_SECRETS_FILE`、`BANK_AGENT_GALILEO_FILE` 可改路径）；两个 Galileo 开关关闭时，`galileo.properties` 中的地址与凭据不会被使用，也不会发起任何 AO 请求。
- 仓库只包含模板 `config/*.example.properties` 与键名映射 `docs/CONFIG-MAPPING.md`；`.gitignore` 忽略 `.env*`、`*secrets*`（模板除外）、`config/*.properties`（模板除外）、日志与数据库文件。
- 在另一台机器上从零配置：

  ```bash
  mkdir -p ~/.config/bank-agent-java && chmod 700 ~/.config/bank-agent-java
  cp config/application-secrets.example.properties ~/.config/bank-agent-java/application-secrets.properties
  chmod 600 ~/.config/bank-agent-java/application-secrets.properties
  # 用编辑器填入 OPENAI_API_KEY（不要在终端 echo 密钥）
  open -e ~/.config/bank-agent-java/application-secrets.properties

  # Galileo 插桩用：按文件第 1 组注释为一种后端填写凭据（默认 Splunk AO SaaS：AO_REALM、AO_O11Y_TOKEN），其余键留空即自动推导
  cp config/galileo.example.properties ~/.config/bank-agent-java/galileo.properties
  chmod 600 ~/.config/bank-agent-java/galileo.properties
  open -e ~/.config/bank-agent-java/galileo.properties
  ```

#### 6.3 OpenAI 模型与 API 配置

| 键 | 默认值 | 说明 |
|---|---|---|
| `OPENAI_API_KEY` | （无） | 仅放私有文件或环境变量。缺失时应用仍能启动，但聊天返回 `503 LLM_NOT_CONFIGURED` |
| `OPENAI_MODEL` | `gpt-5-nano` | 低成本默认模型，已实测支持 Tool Calling 与 JSON Schema |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | 默认 OpenAI 官方端点 |
| `OPENAI_REASONING_EFFORT` | `low` | GPT-5 系列推理强度；切换到非推理模型（如 `gpt-4.1-mini`）时设为空值 |
| `OPENAI_MAX_COMPLETION_TOKENS` | `4096` | 单次输出上限（含推理 token） |
| `OPENAI_TIMEOUT` / `OPENAI_MAX_RETRIES` | `90s` / `1` | 超时与重试 |

**切换模型**：编辑私有文件中的 `OPENAI_MODEL`（必要时同时调整 `OPENAI_REASONING_EFFORT`），然后重启应用即可，无需修改代码。同名环境变量优先于私有文件，例如临时试用：

```bash
OPENAI_MODEL=gpt-5-mini scripts/run.sh
```

为什么选 `gpt-5-nano`：用户要求控制费用；实测它能稳定完成结构化路由与工具调用。代价是单回合 4–10 秒、回答偶尔偏冗长；Java 侧的确定性校验（金额守卫、去重、白名单、确认按钮）保证即使模型出错也不会产生未授权的资金变动。

成本控制：每回合通常 3 次 LLM 调用（路由 + 工具调用 + 最终回答，跨 Agent 回合 5 次），约 2k–8k 输入 token；限制了工具调用次数（6）、Agent 记忆（20 条）、路由历史（8 轮）、消息长度（1000 字符）与最大输出 token。

#### 6.4 启动、停止与健康检查

```bash
cd /Users/hangwe/Library/CloudStorage/OneDrive-Cisco/dev/bank-agent-java

scripts/run.sh                 # 前台启动（Ctrl+C 停止）；源码有变更时自动重新打包
scripts/run.sh --background    # 后台启动，等待健康检查通过；日志 ~/.local/share/bank-agent-java/logs/bank-agent.log
scripts/run.sh --rebuild       # 强制重新打包后启动
scripts/stop.sh                # 停止后台实例

curl -s http://127.0.0.1:8080/actuator/health    # {"status":"UP"}
curl -s http://127.0.0.1:8080/api/status         # 模型名、是否已配置密钥（布尔值）、限额等，不含密钥
open http://127.0.0.1:8080/
```

可选环境变量：`BANK_AGENT_PORT`（默认 8080）、`BANK_AGENT_HOST`（默认 127.0.0.1）、`BANK_AGENT_DATA_DIR`（默认 `~/.local/share/bank-agent-java`）、`BANK_AGENT_SECRETS_FILE`、`BANK_AGENT_GALILEO_FILE`、`BANK_AGENT_TRUSTSTORE`（见第 15 节）、`BANK_AGENT_OTEL_AGENT_JAR`（离线时指定已有的 OTel Java Agent jar）。

Galileo 插桩启动选项（只对本次启动的 JVM 生效，不修改私有配置文件，可与 `--background` 组合）：

```bash
scripts/run.sh --observe              # 挂载 OTel 官方 Java Agent（只对本 JVM）：Trace 发往 Splunk AO
scripts/run.sh --control              # CONTROL_MODE=remote-api：Agent Control 门禁
scripts/run.sh --observe --control    # 两者都开（Console Controls 标签需要两者都开）
scripts/run.sh --trace-content        # DEMO_TRACE_CONTENT=true：把虚构演示文本也发往 AO（默认只发占位符）
scripts/run.sh --galileo-off          # 强制两者关闭（只运行银行 AI 应用）
scripts/preflight.sh                  # 显示推导出的全部 Galileo 地址与来源、Java Agent 校验，并做只读连通性检查
```

`--observe` 时脚本会：①首次通过 Maven Wrapper 下载 OTel Java Agent 2.33.1 到 `$BANK_AGENT_DATA_DIR/otel/` 并校验 SHA-256（失败即中止，离线可用 `BANK_AGENT_OTEL_AGENT_JAR` 指定已有 jar）；②用 `GalileoCli otel-env` 把 `galileo.properties` 翻译成 4 个标准 `OTEL_*` 变量（端点、`X-SF-Token`/`project`/`logstream` 请求头、Resource），只导出到这次 JVM、不打印；③加上 `-javaagent` 与 `config/otel-javaagent.properties`。不修改 JDK、`JAVA_TOOL_OPTIONS` 或私有文件。应用启动日志逐项列出最终配置及来源（`[derived]` / `[override]` / `[default]`，凭据只显示 `<set>`）、Java Agent 是否生效、Project/Agent Stream 的 UUID，以及 Agent Control 的注册结果；配置错误时启动失败并说明原因，而不是悄悄关闭功能。

#### 6.5 如何临时切换 JDK 及恢复到 JDK 21（仅必要时）

本项目**不需要**切换 JDK。若因其他工作需要临时使用别的 JDK，只在当前终端生效：

```bash
sdk list java                       # 查看已安装版本
sdk use java <other-version>        # 仅当前终端会话生效
sdk use java 21-tem                 # 恢复到 JDK 21（或直接关闭该终端）
java -version
```

不要使用 `sdk default ...`（会修改全局默认）。即使当前终端是别的 JDK，`scripts/*.sh` 也会在自身进程中重新选择 Java 21；若当前 `JAVA_HOME` 不是 21，会自动回退到 SDKMAN 的 21。

### 7. Web UI 和 HTTP API 使用方法

**Web UI**（`http://127.0.0.1:8080/`）：

- 顶部显示 `DEMO ACCOUNT`、`Demo / Simulated Banking` 与当前模型名；
- 左侧：演示账户余额、已登记收款人、单笔上限、**示例问题**（点击即可填入）、**新建会话**、**重置演示数据**；
- 中间：会话消息、"助理正在处理…"等待指示；当 Payments Agent 生成草稿时出现**转账草稿卡片**，含 **确认** / **取消** 按钮与有效期；
- 右侧：**执行详情**（来自后端真实执行记录）——路由意图、选中的 Agent、每个 Tool 调用及耗时、LLM 调用次数与 token、草稿事件；开启 Galileo 插桩时还显示 **Agent Control 检查**（每个检查时机/步骤的结果、命中的规则与原因、耗时）和 **Trace**（OTel `trace_id` 与 AO Trace ID）；
- Galileo 插桩相关：页头显示 Observe（`OTel Java Agent` / `关` / `未生效（未挂载 Java Agent）`）与 Control 状态；Control 拒绝确认时草稿卡片显示原因（未扣款）和该次确认的 Trace ID；Control 服务不可用而只读步骤按 fail-open 继续时，消息下方显示“部分只读步骤未经安全检查”的警告。

**HTTP API**：

| 方法与路径 | 请求体 | 说明 |
|---|---|---|
| `POST /api/chat` | `{"conversationId"?: "...", "message": "..."}` | 返回 `conversationId`、`newConversation`、`messageId`、`answer`、`intent`、`selectedAgents`、`executedTools`、`agentSteps`、`toolCalls`、`llmCalls`、`pendingDraft`、`draftEvents`、`durationMs`、`model`；开启插桩时另有 `controlDecisions`（每次检查的时机、步骤、结果、规则、原因、耗时）、`traceId`、`aoTraceId`（Observe 关闭时为空） |
| `POST /api/payments/drafts/{draftId}/confirm` | `{"conversationId": "..."}` | 用户点击确认后执行；重复确认返回 `replayed=true`。Observe 生效时响应头带 `X-Bank-Trace-Id` / `X-Bank-AO-Trace-Id` |
| `POST /api/payments/drafts/{draftId}/cancel` | `{"conversationId": "..."}` | 取消待确认草稿 |
| `POST /api/demo/reset` | 无 | 仅 loopback 地址可调用，否则 `403 LOCAL_ACCESS_ONLY` |
| `GET /api/demo/profile` | — | 脱敏账户概况与收款人（UI 左侧栏） |
| `GET /api/status` | — | 应用/模型状态；Galileo 插桩的 `observability`（开关、`otelJavaAgentActive`：Java Agent 是否真正生效、配置档、OTLP 主机、Project/Stream 名称与 UUID、Console 链接）与 `control`（模式、已绑定规则、失败模式、Control 事件通道），不含密钥 |
| `GET /actuator/health` | — | 健康检查 |

错误统一返回 `{"code","message","status","timestamp"}`，从不包含堆栈或密钥。常见错误码：`VALIDATION_ERROR`(400)、`UNSUPPORTED_MEDIA_TYPE`(415)、`CONVERSATION_NOT_FOUND`(404，草稿操作时)、`DRAFT_NOT_FOUND`(404)、`DRAFT_CONVERSATION_MISMATCH`(403)、`DRAFT_CANCELLED` / `DRAFT_EXPIRED` / `DRAFT_ALREADY_EXECUTED` / `DRAFT_FAILED`(409)、`INSUFFICIENT_FUNDS` 等(422)、`CONVERSATION_BUSY`(409)、`LLM_NOT_CONFIGURED`(503)、`LLM_UPSTREAM_ERROR`(502)；Agent Control：`CONTROL_DENIED`(403)、`CONTROL_STEER_REQUIRES_USER_ACTION`(409)、`CONTROL_UNAVAILABLE`(503，服务不可用而非策略决定)。

示例：

```bash
curl -s -X POST http://127.0.0.1:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"我现在的活期账户余额是多少？"}'
```

### 8. 示例银行数据与重置方法

- 演示客户：王小明（演示用户），`CUST-DEMO-001`；活期 `DEMO-****-0001` 余额 **52,368.50 CNY**，储蓄 `DEMO-****-0002` 余额 **120,000.00 CNY**；
- 已登记收款人：张三（BEN-001）、李四（BEN-002）、王五（BEN-003），银行名称均为虚构；
- 25 笔交易覆盖"上个自然月 + 本月"，类别包括住房、餐饮、工资收入、购物、交通、水电燃气、转账、娱乐、医疗、退款、通讯、利息。

**日期生成策略**（`service/DemoDataService.java`）：以应用时区 `Asia/Shanghai` 的"今天"为基准**相对生成**、固定金额：

- 上个自然月使用固定日（1–27 日，所有月份都存在），因此"上个月"的统计结果每天都一致：消费合计 **10,689.00**（不含 1,000.00 转账），最多类别 **购物 4,878.00**，收入 18,000 + 199 退款（另有储蓄利息 120）；
- 本月 8 笔交易按日 1–8 生成，但**不晚于当前时间**（例如每月 1 日凌晨启动时，会被压缩到当天已过去的时间内），保证不会出现未来交易；
- 期末余额固定，`balance_after` 按时间倒推计算；首次启动时若数据库为空才写入种子数据，重启不会叠加数据。

**重置**（恢复余额、交易，清除草稿、审计与所有会话）：

```bash
scripts/reset-demo.sh          # 应用运行中：调用本机 reset API；未运行：删除本地 H2 文件，下次启动重新生成
scripts/reset-demo.sh --yes    # 跳过确认提示
```

或点击 UI 左侧的"重置演示数据"。

演示时建议使用专用数据目录（`export BANK_AGENT_DATA_DIR=~/.local/share/bank-agent-java-demo`），重置不影响默认目录中的数据，见 [`docs/demo/DEMO-GUIDE.md`](docs/demo/DEMO-GUIDE.md)。

### 9. 自动化测试与验收结果

```bash
scripts/test.sh        # 等价于 ./mvnw -B clean verify（项目内选择 Java 21）
```

测试完全离线：Surefire/Failsafe 强制 `OPENAI_API_KEY` 为空、私有配置路径指向不存在的文件；集成测试使用内存 H2、固定时钟（2026-03-15 10:00）和脚本化的 `ScriptedChatModel`（真实走 LangChain4j AiServices 与 Java Tools，只替换 OpenAI 网络调用）。

| 测试类 | 类型 | 覆盖内容 |
|---|---|---|
| `AmountMentionsTest`、`AmountParserTest` | 单元 | 金额解析（阿拉伯/中文数字、万/千）与金额守卫 |
| `SupervisorAgentTest` | 单元 | 单域、跨域、澄清/超范围、重复步骤去重、Agent 异常、输入组合 |
| `LlmSafetyUnitTest`、`DemoControllerLoopbackTest` | 单元 | 未配置模型快速失败、密钥遮蔽、本机地址判定 |
| `LlmIntentRouterIT` | 集成 | 结构化路由、无效输出兜底、步骤上限、历史上下文 |
| `BankingServicesIT` | 集成 | 余额、最近交易排序、上月消费聚合、月初凌晨种子不产生未来交易、重置幂等 |
| `PaymentServiceIT` | 集成 | 草稿不扣款；确认后余额/交易/审计一致；重复确认；新草稿取代旧草稿；未登记收款人、非法金额、超限、余额不足（草稿时与确认时）、过期、跨会话、取消后确认、已执行不可取消；金额守卫审计 |
| `AgentFlowIT` | 集成 | 经 Supervisor → AiServices → 真实 Tool → H2 的完整回合：执行记录、跨 Agent 合并、草稿、金额守卫、同会话记忆延续与跨会话隔离、超范围问题 |
| `ApiControllersIT` | 集成 | `/api/chat` 输入校验（空、超长、坏 JSON、415）、过期会话、LLM 失败无堆栈；确认/重放/取消/409/403/404；本机重置；UI、健康检查、状态接口无密钥 |
| `AoEndpointResolverTest`、`AoTargetResolverTest`、`TraceContentPolicyTest` | 单元（插桩） | 三种后端的配置推导与校验；Project/Agent Stream 查找或创建（Mock HTTP）；Trace 内容默认不导出与脱敏 |
| `ControlGatewayTest` | 单元（插桩） | 用协议验证时录下的真实响应制作的 fixture：ALLOW/OBSERVE/DENY/STEER、未知动作、优先级、evaluator 错误、HTTP 错误、超时、Runtime Token、预过滤 |
| `OtelAgentEnvironmentTest` | 单元（插桩） | `galileo.properties` → 标准 `OTEL_*` 变量（端点、请求头、Resource、编码） |
| **`OtelJavaAgentIT`** | 集成（插桩，黑盒） | **独立 JVM 加载真实 OTel Java Agent**，本地 OTLP 接收器 + OpenAI / AO / Agent Control Mock：四种开关组合、`/v2/trace/otlp` 与路由头、完整 Span 树、无重复与噪声、无密钥正文堆栈、Observe 关闭时零导出、jar 版本与 SHA-256 |
| `ObserveOnlySpanIT` | 集成（插桩） | 只开 Observe：LangChain4j 默认路径（无 Control 包装）仍得到完整的 Agent → LLM → Tool 树 |
| `OtelSpanStructureIT` | 集成（插桩） | Observe + Control：父子树、token、会话、默认不导出正文、Control Span 挂在 LLM / Tool / 根下、STEER token 求和、确认 Trace、Context 不泄漏 |
| `ControlledToolFlowIT` | 集成（插桩） | Mock Control：PRE DENY 不建草稿、POST DENY 不泄露并撤回草稿、STEER 次数受限、确认前拦截不扣款、fail-open/fail-closed |
| `ControlDisabledRegressionIT` | 集成（插桩） | 两个开关关闭时与没有插桩时一致：无 Span、无 Control 请求、无后端查找、原模型 |

最近一次结果：**119 个测试全部通过**（52 单元 + 67 集成），`BUILD SUCCESS`。银行 AI 应用原有的 49 个测试未修改。测试把 `BANK_AGENT_GALILEO_FILE` 指向不存在的文件并清空所有 AO 凭据，不会访问 OpenAI 或 AO（黑盒测试使用的 Agent jar 与其他构建依赖一样来自 Maven Central / `~/.m2`）。

银行 AI 应用的真实 OpenAI 在线验收（`gpt-5-nano-2025-08-07`）：9 个业务问题（即 UI 左侧的示例问题）全部通过，并验证了 UI 确认执行、重复确认、跨会话拒绝、取消后确认、金额守卫等场景。详细记录（每题的 Agent、Tool、耗时、token、结果、首次验收发现的问题及修复）见 [`docs/app/VALIDATION.md`](docs/app/VALIDATION.md)。Galileo 插桩的在线验收见第 12 节。

## 第二部分：Galileo 插桩（Observe 与 Agent Control）

### 10. 做了什么

**Observe（自动优先，三层）**：

| 层 | 谁提供 | 得到什么 | 业务代码改动 |
|---|---|---|---|
| ① OTel 官方 Java Agent 2.33.1 | OTel 官方 | 每个 HTTP 请求一个 Span（Trace 根，AO 中为 `http`）、调用上下文、OTLP/HTTP 导出、Resource | 无：`-javaagent` + 4 个标准 `OTEL_*` 变量 |
| ② LangChain4j 官方扩展点 | LangChain4j 官方 + 本项目 1 个适配类 `LangChain4jOtelBridge` | 每次模型调用一个 `chat <model>`（真实 token）、每个 Agent 一个 `invoke_agent`、每个 Tool 一个 `execute_tool`（在 Java 方法运行前开始） | 注册一次（`LlmConfig`、`AgentConfig`） |
| ③ 手写 | 本项目 | 一轮对话的根 `invoke_agent BankingAssistant`（会话 ID → AO Session）；UI 确认扣款 `execute_tool payment.confirm`（独立 Trace，同一 Session） | 2 处，框架没有对应事件 |

- 同一会话的多轮 Trace（包括确认按钮的独立 Trace）通过 `gen_ai.conversation.id` 归入同一 AO Session；默认不导出对话正文、不导出堆栈。
- 关闭了会产生重复或噪声 Span 的自动采集（OpenAI 的原始 HTTP 请求、ORM 层、JDBC），原因写在 [`config/otel-javaagent.properties`](config/otel-javaagent.properties)。
- 需要如实说明：Java Agent 自带的 GenAI 采集只支持 OpenAI 官方 Java SDK，不理解 LangChain4j 的 Agent / Tool，所以第 ② 层是必要的。

**Control（只在业务关口）**：

- Console 中定义的 Agent Control 规则（Server execution）由服务端返回决策，Java 在业务关口真正执行：用户消息与最终回答（`banking-assistant`）、路由与各 Agent 的模型调用、全部 Tool、UI 确认（`payment.confirm`）。PRE 的 DENY 在写入前阻止（不生成草稿、不扣款）；POST DENY 不把结果交给前端并撤回写操作；STEER 有限次改写；OBSERVE 只记录。服务不可用时，写步骤 fail-closed，只读步骤默认 fail-open 并明确标记为“未经检查”。规则修改后几秒内生效，无需重启。
- Control 的门禁包装只在 `--control` 时插入；只开 Observe 时 LangChain4j 走默认路径。
- **Controls 标签**：每个评估结果写成与官方 `splunk-ao-python` SDK 相同属性的 control span，挂在被检查步骤下，由**同一个 Java Agent、同一条 OTLP 通道**发往 AO。
- 没有使用 Python/Node sidecar，没有自研 Collector；Control 部分是本项目基于已实测 REST 接口写的 Adapter，不是官方 Java SDK。

三层的代码、启动方式和剩余手工点见 [`docs/galileo/JAVA-INSTRUMENTATION.md`](docs/galileo/JAVA-INSTRUMENTATION.md)；为什么选这条路线、放弃了哪些方案（`langchain4j-observation`、OpenInference 适配器等）见 [`docs/galileo/ARCHITECTURE.md`](docs/galileo/ARCHITECTURE.md) 第 6 节。

### 11. 如何开启与配置

1. 按第 6.2 节准备 `~/.config/bank-agent-java/galileo.properties`：Splunk AO SaaS 只需 `AO_REALM`、`AO_O11Y_TOKEN`；其余地址按官方规则自动推导（Galileo 两种后端也可配置，但**未在线验证**，见 [`docs/CONFIG-MAPPING.md`](docs/CONFIG-MAPPING.md)）。
2. `scripts/preflight.sh` 查看推导结果、Java Agent 校验与只读连通性检查。
3. `scripts/run.sh --background --observe`（只观测）或 `--observe --control`（完整演示）。Project `bank-agent-java` 与 Agent Stream `bank-java-demo` 不存在时由应用自动创建。`/api/status` 的 `observability.otelJavaAgentActive=true` 表示 Java Agent 真正生效。
4. 在 Console 的 Agent Stream → Controls 中创建规则；演示用的 6 条规则见 [`docs/demo/DEMO-GUIDE.md`](docs/demo/DEMO-GUIDE.md) 附录 A。

在你自己的 Java 应用中复用 Observe：加 `-javaagent:opentelemetry-javaagent.jar`，设置 `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`、`OTEL_EXPORTER_OTLP_TRACES_HEADERS`、`OTEL_RESOURCE_ATTRIBUTES`、`OTEL_SERVICE_NAME`，再把 `LangChain4jOtelBridge` 注册到模型与 AI Service 上，见 [`docs/galileo/QUICKSTART.md`](docs/galileo/QUICKSTART.md)。

### 12. 验收状态

```text
Observe/OTLP（OTel Java Agent + LangChain4j 监听）: PASS
Control/Remote-API Enforcement: PASS
Control/Console Control Tab: PASS（AO REST API 回读）；Console 界面截图: NOT RUN
```

- 离线：119 个测试，其中 `OtelJavaAgentIT` 在独立 JVM 中加载真实 `-javaagent`，覆盖四种开关组合。
- 在线（2026-11-09，Splunk AO SaaS `au0`，真实 OpenAI 与 Agent Control）：Observe-only、Control-only、两者都开、全部关闭四种模式；6 条演示规则（OBSERVE、PRE DENY、POST DENY、STEER、注入、确认前 DENY）、规则热更新、故障演练、9 个业务问题回归全部符合预期；AO 回读到 http → agent → llm（真实 token）/ tool 树、正确的 Session、挂在被检查步骤下的 control span。
- 已知问题（Trace 名称为根 HTTP Span、AO 有时不显示 Input/Output、失败状态只在 metadata 等）、真实 Trace ID、改造前后对比，以及 2026-11-02 旧实现的历史验收（含截图）见 [`docs/galileo/VALIDATION.md`](docs/galileo/VALIDATION.md)。

```bash
scripts/run.sh --background --observe --control
scripts/galileo-smoke.sh --yes                  # 显式同意后才执行：3 个只读问题 + AO 回读（真实 OpenAI，产生少量费用）
scripts/galileo-smoke.sh --trace <traceId>...   # 只读：回读已有 Trace 的 Span 树、token、control span 及所属 Session
```

### 13. 专项文档

| 文档 | 内容 |
|---|---|
| [`docs/galileo/QUICKSTART.md`](docs/galileo/QUICKSTART.md) | **5 分钟版本**：Observe 三层、3 条命令、Control 在哪里手工、一张对照表 |
| [`docs/galileo/JAVA-INSTRUMENTATION.md`](docs/galileo/JAVA-INSTRUMENTATION.md) | 面向非 Java 读者：三层插桩、谁做了什么、Java Agent 配置、最终剩下的手工修改点 |
| [`docs/galileo/ARCHITECTURE.md`](docs/galileo/ARCHITECTURE.md) | 数据流、单一导出通道、开关矩阵、源码职责、**选型与舍弃方案**、改造前后对比 |
| [`docs/galileo/AGENT-CONTROL.md`](docs/galileo/AGENT-CONTROL.md) | Agent Control REST 契约、决策映射、各执行点的处理、支付确认保护、哪些必须手工 |
| [`docs/galileo/OTEL-SEMANTIC-MAPPING.md`](docs/galileo/OTEL-SEMANTIC-MAPPING.md) | 每类 Span 的来源（自动 / 框架监听 / 手写）、属性与 AO 字段、敏感信息策略 |
| [`docs/galileo/PROTOCOL-VERIFICATION.md`](docs/galileo/PROTOCOL-VERIFICATION.md) | 协议验证记录（含重构后复核）与向 Galileo 团队提出的问题 |
| [`docs/galileo/VALIDATION.md`](docs/galileo/VALIDATION.md) | 当前实现的验收结果、已知问题；旧实现的历史基线 |
| [`docs/CONFIG-MAPPING.md`](docs/CONFIG-MAPPING.md) | 必填项、`OTEL_*` 推导、高级覆盖 |

## 第三部分：AI 应用与 Galileo 演示

### 14. 演示故事线

完整的操作步骤、每一步在应用和 Console 中的预计现象、演示前准备、复位和现场应对，统一写在 [`docs/demo/DEMO-GUIDE.md`](docs/demo/DEMO-GUIDE.md)。概要：

| 幕 | 主题 | 关键操作 |
|---|---|---|
| 一 | 认识银行助理 | 不开任何插桩：问余额、上月消费；看右侧执行详情中的路由、Tool 与 token |
| 二 | 一键开启 Observe | `--observe` 重启：只多了 `-javaagent` 和一个配置文件；业务代码没有 Span |
| 三 | 在 Splunk AO 中看见每一步 | Session、http → agent → llm（token）/ tool 树 |
| 四 | 策略在 Console，拦截在 Java | `--observe --control`：OBSERVE、PRE DENY、POST DENY、STEER、提示词注入；Controls 标签 |
| 五 | 改规则不用改代码、不用重启 | Console 中停用/恢复一条规则，同一句话的结果随之改变 |
| 六 | 必须手写的支付保护 | 草稿 → 人工确认、取消、大额确认前拦截、非法转账被 Java 拒绝、确认是独立 Trace |
| 七（可选） | 故障与开关对比 | 策略服务不可用时只读放行并警示、写操作拒绝；只开 Control 时拦截照样生效 |

```bash
export BANK_AGENT_DATA_DIR=~/.local/share/bank-agent-java-demo   # 演示专用数据目录
scripts/run.sh --background --observe --control
scripts/reset-demo.sh --yes
```

UI 左侧的 9 个示例问题覆盖第一、六幕的业务场景；第四幕的输入原文和对应规则见演示指南。

## 附录

### 15. 常见问题与故障排查

演示现场的问题与应对见 [`docs/demo/DEMO-GUIDE.md`](docs/demo/DEMO-GUIDE.md) 第 4 节。

| 现象 | 原因与处理 |
|---|---|
| `PKIX path building failed` / `unable to find valid certification path` | 公司网络（如 Cisco Secure Access）对 HTTPS 做 TLS 检查，JDK 默认 cacerts 不信任其根证书。`scripts/run.sh` 会在 `~/.local/share/bank-agent-java/tls/truststore.p12` 生成**项目专用** truststore（JDK cacerts + macOS 钥匙串中的系统/企业根证书），只对本应用进程生效，不修改 JDK。设置 `BANK_AGENT_TRUSTSTORE=none` 可禁用，或 `BANK_AGENT_TRUSTSTORE=/path/to/store.p12` 使用自定义 truststore。**请始终用 `scripts/run.sh` 启动**，直接 `java -jar` 可能遇到此错误 |
| 聊天返回 `LLM_NOT_CONFIGURED` | 私有文件不存在或 `OPENAI_API_KEY` 为空；运行 `scripts/preflight.sh` 检查 |
| 聊天返回 `LLM_UPSTREAM_ERROR` | OpenAI 返回错误（401 密钥无效、404 模型不可用、429 限流/额度、网络中断）。查看 `~/.local/share/bank-agent-java/logs/bank-agent.log`（密钥已遮蔽） |
| `Java 21 not found` | 安装 JDK 21（如 `sdk install java 21-tem`）或设置 `JAVA_HOME` 指向 JDK 21 |
| 端口 8080 被占用 | `BANK_AGENT_PORT=8081 scripts/run.sh`，或 `scripts/stop.sh` 停止已有实例 |
| H2 `Database may be already in use` | 已有实例在运行（同一数据目录）；先 `scripts/stop.sh` |
| 回答较慢（4–10 秒）或较啰嗦 | `gpt-5-nano` 为推理模型；可改用 `OPENAI_REASONING_EFFORT=minimal` 或更换模型 |
| `CONVERSATION_BUSY` | 同一会话上一条消息尚未完成，等待后重试 |
| 余额与预期不一致 | 之前演示中执行过转账；运行 `scripts/reset-demo.sh` |
| 刚才的会话"消失" | 会话保存在内存中，重启或 2 小时无活动后过期；系统会自动开启新会话 |
| 开启 `--observe` / `--control` 后启动失败 | 终端或启动日志会说明原因（OTel Java Agent 下载/校验失败、缺少 `AO_REALM`/`AO_O11Y_TOKEN`、Token 无权限、Agent Control 不可达等）。运行 `scripts/preflight.sh` 查看推导出的全部地址、Agent 校验与只读检查结果；设计上不会悄悄降级为关闭 |
| OTel Java Agent 下载失败 / 离线环境 | 首次 `--observe` 通过 Maven Wrapper 从 Maven Central 下载 `io.opentelemetry.javaagent:opentelemetry-javaagent:2.33.1`；离线时把该 jar 复制到本机并设置 `BANK_AGENT_OTEL_AGENT_JAR=<路径>`（同样校验 SHA-256） |
| 页头显示 `Observe: 未生效（未挂载 Java Agent）` | 开关为开，但 JVM 没有挂载 Agent（例如直接 `java -jar`）。请用 `scripts/run.sh --observe` 启动 |
| Console 中暂时找不到 Trace | AO 入库有 30–90 秒延迟；在 Project `bank-agent-java` → Agent Stream `bank-java-demo` 中按 External Id（`traceId`）查找；`/api/status` 的 `observability.otelJavaAgentActive` 应为 `true`；导出错误由 Java Agent 写在应用日志中 |
| Console 中 Trace 名称都是 `POST /api/chat` | Trace 的根是 Java Agent 自动生成的 HTTP Span；对话内容在第二层 `invoke_agent BankingAssistant`（已知问题 K7） |
| Trace 中 Span 的 Input/Output 为空 | 默认只导出占位符；此外 AO 有时不显示 Java 发送的内容（已知问题 K1，见 [`docs/galileo/VALIDATION.md`](docs/galileo/VALIDATION.md)），父子树、token 与 Control 不受影响 |
| 想在 Tool 下看到 SQL | 本 Demo 默认关闭 JDBC 自动采集（嵌入式 H2 的文件路径会进入 Span、启动 SQL 会产生无关 Trace）；设置 `OTEL_INSTRUMENTATION_JDBC_ENABLED=true` 后用 `--observe` 启动即可开启 |
| 回答提示“安全检查服务暂时不可用” / `CONTROL_UNAVAILABLE` | Agent Control 超时或返回错误；写操作按 fail-closed 不执行（未扣款），稍后重试。这不是策略拒绝；日志中有 `Agent Control unavailable` 警告 |
| 回答提示“已被安全策略拦截” / `CONTROL_DENIED` | Console 中的规则拒绝了该步骤；UI 执行详情和 Console Controls 标签显示规则名与原因 |

### 16. 安全、限制与非生产用途声明

- **非生产用途**：单一固定演示用户，无登录/认证；所有数据虚构；转账仅为本地模拟记账。
- 服务默认只绑定 `127.0.0.1`；重置接口仅允许 loopback 地址；H2 Console 关闭；Actuator 仅暴露 `health`。
- 发送给 OpenAI 的只有模拟数据与用户在 UI 输入的文字；不发送 Golden Demo 密钥、AO 配置或真实个人信息。
- 开启 Galileo 插桩时：发往 Splunk AO 的 Span 默认**不含正文**（占位符、只导出参数名和结果长度）、不含堆栈、不含 HTTP 请求正文与请求头，JDBC 自动采集关闭；发往 Agent Control 的是受控步骤的输入/输出（评估必需）。AO 凭据只在仓库外的 `galileo.properties`（0600）中；`--observe` 时 Token 以标准变量 `OTEL_EXPORTER_OTLP_TRACES_HEADERS` 只传给应用 JVM 的环境（不进入命令行参数、日志或文件）；日志、`/api/status`、脚本只显示是否已设置。
- OTel Java Agent：版本与 SHA-256 固定，缓存在仓库外，只通过 `-javaagent` 挂到本应用的 JVM；不修改 JDK、`JAVA_TOOL_OPTIONS` 或其他项目。
- 密钥只存在于仓库外的 0600 私有文件中；页面、API、日志、测试和文档均不包含密钥。
- 已知限制：
  - 会话与 Agent 记忆在内存中，重启即丢失（数据库数据保留）；
  - 中文金额解析覆盖常见写法，但"一万二"这类口语省略写法会被解析为 10002，守卫会因此拒绝并要求用户写明金额（偏安全）；
  - `gpt-5-nano` 回答风格偶尔冗长；LLM 输出本身具有不确定性，安全性依赖 Java 侧的确定性校验，而非提示词；
  - 未实现多币种、多客户、定时转账、真实银行接口；
  - 未实现基于 Trace 的 Galileo 评估（Evaluators）：Span 已按 GenAI 语义约定设置 `gen_ai.input.messages` / `gen_ai.output.messages` / `gen_ai.tool.call.*`，开启 `DEMO_TRACE_CONTENT=true` 后会导出经过过滤的虚构文本，可作为评估输入；但默认只导出占位符，且 AO 有时不显示 Java 发送的内容（已知问题 K1），在依赖 Trace 内容做评估前需要先解决。

### 17. 参考资料与开源项目来源

- Azure-Samples，Multi-Agent Banking Assistant with Java and LangChain4j（借鉴 Supervisor / Account / Transactions / Payments 业务分工；未移植 Azure 相关组件与 React 前端）：<https://github.com/Azure-Samples/agent-openai-java-banking-assistant>
- LangChain4j — Spring Boot Integration：<https://docs.langchain4j.dev/tutorials/spring-boot-integration/>
- LangChain4j — AI Services：<https://docs.langchain4j.dev/tutorials/ai-services/>
- LangChain4j — Tools / Function Calling：<https://docs.langchain4j.dev/tutorials/tools/>
- LangChain4j — Chat Memory：<https://docs.langchain4j.dev/tutorials/chat-memory/>
- LangChain4j — OpenAI Integration：<https://docs.langchain4j.dev/integrations/language-models/open-ai/>
- Spring Boot System Requirements：<https://docs.spring.io/spring-boot/system-requirements.html>
- OpenTelemetry Java 零代码插桩（Java Agent）：<https://opentelemetry.io/docs/zero-code/java/agent/>；配置：<https://opentelemetry.io/docs/zero-code/java/agent/configuration/>；支持的库：<https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/supported-libraries.md>
- LangChain4j — Observability（ChatModelListener、AI Service 监听）：<https://docs.langchain4j.dev/tutorials/observability/>
- Splunk Agent Observability：<https://agent-observability-docs.splunk.com/what-is-splunk-agent-observability>
- Splunk AO — Distributed Tracing with OpenTelemetry：<https://agent-observability-docs.splunk.com/sdk-api/logging/distributed-tracing-otel>
- Splunk AO — Agent Control 概览与初始化：<https://agent-observability-docs.splunk.com/concepts/agent-control/overview>、<https://agent-observability-docs.splunk.com/how-to-guides/agent-control/initialize-and-configure-agent-control>
- 官方 Python SDK（协议与属性参考，`f7a20ed`）：<https://github.com/splunk/splunk-ao-python>
- OpenTelemetry Java：<https://opentelemetry.io/docs/languages/java/>；GenAI 语义约定：<https://opentelemetry.io/docs/specs/semconv/gen-ai/>
