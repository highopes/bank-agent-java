# Galileo 插桩的 OTel 语义映射（Java Span → Splunk AO）

属性名与官方 `splunk-ao-python` SDK（`f7a20ed`，`converter/attribute_mapping.py`）在 o11y 模式下发送的 OTLP 属性一致；“AO 中显示为”一列来自在线验收时实测回读的 AO 记录（`GET /ao/api/projects/{id}/traces/{uuid}`），不是推测。常量定义在 `observability/GenAiAttributes.java`。

## 1. Resource 与路由

| 属性 / 请求头 | 值 | 作用 |
|---|---|---|
| `service.name` | `bank-agent-java`（`OTEL_SERVICE_NAME`） | 标准 OTel |
| `service.version` | Maven 版本，如 `0.2.0-SNAPSHOT` | 标准 OTel |
| `deployment.environment.name` | `local-demo` | 标准 OTel |
| `splunk_ao.project.name` / `splunk_ao.logstream.name`（o11y）<br/>`galileo.project.name` / `galileo.logstream.name`（galileo） | `bank-agent-java` / `bank-java-demo` | 路由（与官方 SDK 相同）；galileo 配置档**未在线验证** |
| 请求头 `project` / `logstream` | 同上 | 路由（协议验证：必须用 `logstream`，`agentstream` 会进入 “Unassigned”） |
| 请求头 `X-SF-Token`（o11y）/ `Galileo-API-Key`（galileo） | 凭据 | 认证 |

## 2. Span 类型

AO 根据 `gen_ai.operation.name`（control 还需要 `galileo.span.kind`）判断 Span 类型。

### 2.1 Agent span —— `invoke_agent <Name>`

创建位置：`ChatService`（`BankingAssistant`，根）、`SupervisorAgent`（`SupervisorAgent`）、`LangChainDomainAgentGateway`（`AccountAgent` / `TransactionsAgent` / `PaymentsAgent`）。`SpanKind.INTERNAL`。

| 属性 | 值 | AO 中显示为 |
|---|---|---|
| `gen_ai.operation.name` | `invoke_agent` | `type=agent`（根 Span 同时生成 `type=trace` 的记录） |
| `gen_ai.agent.name` | `BankingAssistant` 等 | Span 名称 |
| `gen_ai.provider.name` | `openai` | metadata |
| `gen_ai.conversation.id` | 会话 UUID | 决定 **Session**（同一会话的多轮 Trace 在同一 Session） |
| `gen_ai.input.messages` / `gen_ai.output.messages` | GenAI 消息 JSON；默认为占位符 `[omitted: DEMO_TRACE_CONTENT=false]` | Input / Output（见 K1：有时不显示） |
| `bank.content.captured` | `true`/`false` | metadata |
| `bank.intent`、`bank.selected_agents`、`bank.route.fallback` | 路由结果（Supervisor、根 Span） | metadata |

### 2.2 LLM span —— `chat <model>`

创建位置：`GovernedChatModel.doChat`（每次真实模型调用一个 Span，包括 STEER 改写）。`SpanKind.CLIENT`。

| 属性 | 值 | AO 中显示为 |
|---|---|---|
| `gen_ai.operation.name` | `chat` | `type=llm` |
| `gen_ai.provider.name` | `openai` | provider |
| `gen_ai.request.model` / `gen_ai.response.model` | `gpt-5-nano` / `gpt-5-nano-2025-08-07` | model |
| `gen_ai.response.id`、`gen_ai.response.finish_reasons` | OpenAI 响应 ID、`STOP` / `TOOL_EXECUTION` | metadata |
| `gen_ai.usage.input_tokens` / `gen_ai.usage.output_tokens` | 真实 token 数 | `metrics.num_input_tokens` / `num_output_tokens` / `num_total_tokens`，并据此计算 `cost` |
| `gen_ai.input.messages` / `gen_ai.output.messages` | 默认占位符；开启 `DEMO_TRACE_CONTENT` 后为过滤后的消息（含 tool_call） | Input（消息列表）/ Output |
| `bank.llm.step` | `supervisor-router`、`account-agent`、`banking-assistant`（最终回答改写）等 | metadata（对应 Agent Control 步骤名） |
| `bank.control.steer_attempt` | `1`、`2` | metadata：第几次 STEER 改写 |

### 2.3 Tool span —— `execute_tool <toolName>`

创建位置：`GovernedToolExecutor`。`SpanKind.INTERNAL`。

| 属性 | 值 | AO 中显示为 |
|---|---|---|
| `gen_ai.operation.name` | `execute_tool` | `type=tool` |
| `gen_ai.tool.name`、`gen_ai.tool.call.id` | 工具名、模型给出的调用 ID | 名称、`tool_call_id` |
| `gen_ai.tool.call.arguments` | 默认 `{"captured":false,"argumentNames":[…]}`（只有参数名） | Input |
| `gen_ai.tool.call.result` | 默认 `{"captured":false,"resultChars":N}`；被 Control 拦截时为 `{"error":"CONTROL_DENIED",…}` | Output |
| `bank.tool.access` | `READ` / `WRITE` | metadata |
| `bank.control.pre.verdict` / `.latency_ms` / `.control` / `.failure`，`bank.control.post.*` | Control 结果摘要 | metadata（**不会**出现在 Controls 标签，见 [`AGENT-CONTROL.md`](AGENT-CONTROL.md) 第 8 节） |
| `bank.control.blocked` | `true` | metadata |

### 2.4 UI 确认 / 取消 —— `payment.confirm`、`payment.cancel`

创建位置：`PaymentController`。**新的根 Span**（`setNoParent()`），通过 Span link 指向创建草稿的 `execute_tool createTransferDraft` Span（`DraftTraceRegistry`），并带同一个 `gen_ai.conversation.id`，因此与聊天在同一 AO Session。

| 属性 | 值 |
|---|---|
| `gen_ai.operation.name` / `gen_ai.tool.name` | `execute_tool` / `payment.confirm` |
| `bank.operation` | `confirm` / `cancel` |
| `bank.draft.ref` | 草稿 ID 的短哈希（不导出草稿 ID 本身） |
| `bank.outcome` | `EXECUTED`、`REPLAYED`、`CANCELLED`、`CONTROL_DENIED`、`DRAFT_CANCELLED` 等 |

HTTP 响应头 `X-Bank-Trace-Id` / `X-Bank-AO-Trace-Id`（成功和失败都有，仅在 Observe 开启时）把一次按钮点击与 AO Trace 对应起来；UI 草稿卡片显示该 Trace ID。

### 2.5 Control span —— `<规则名>`

创建位置：`ControlEventReporter`，每个评估过的规则一个，父 Span 为被检查的步骤；开始时间按服务端返回的 `execution_duration_ms` 回推。

| 属性 | 值 | AO 中显示为 |
|---|---|---|
| `galileo.span.kind` | `control` | `type=control`（盾牌图标） |
| `gen_ai.operation.name` | `control` | — |
| `agent_control.control_id` / `control_name` / `agent_name` | `18` / 规则名 / `bank-agent-java` | Controls 标签：控件名、View control 链接 |
| `agent_control.check_stage` | `pre` / `post` | 检查时机（Stage） |
| `agent_control.applies_to` | `tool_call` / `llm_call` | Applies To |
| `agent_control.evaluator_name` / `selector_path` | `list` / `input.beneficiary` | Evaluators、Trigger if |
| `agent_control.action` / `matched` / `confidence` | `deny` / `true` / `1.0` | Triggered（matched=true 时） |
| `agent_control.control_execution_id` | 服务端执行 ID | metadata |
| `agent_control.error_message` | evaluator 错误时 | Span 状态 ERROR |
| `gen_ai.output.messages` | `{"action","matched","confidence"}` | Output |
| `gen_ai.input.messages` | 默认占位符 | **不显示**（AO 不映射 control span 的 Input，K3） |

## 3. 错误表达

| 情况 | Span 上的表达 | AO 中显示为 |
|---|---|---|
| 业务或 LLM 异常 | `error.type=<错误码>`、OTel 状态 `ERROR`（简短说明）、`exception` 事件（type + 遮蔽后的 message，**无堆栈**）、`splunk_ao.status_code=<HTTP 状态>` | metadata 中 `error.type`、`otel.status_code: ERROR`、`exception.type`、`splunk_ao.status_code`；记录的 `status_code` 仍为 0（K2） |
| Tool 被 Control 拦截 | `error.type=control_denied` / `control_steer` / `control_unavailable` / `control_denied_output`，`splunk_ao.status_code` 403 / 409 / 503 | 同上 |
| OTLP 导出失败 | 不影响业务；计入 `/api/status` 的 `failedExportBatches` 与 `lastExportFailure`，日志 WARN（不含请求头） | — |

## 4. ID 对应

| 位置 | 形式 | 例子 |
|---|---|---|
| OTel / 应用响应 `traceId` / Console External Id | 32 位十六进制 | `f347f5997ca9e8602840e4c92ae9a85b` |
| AO Trace UUID（API、Console URL 中的 `traceId=`） | 第 13 位改为 `4`、第 17 位改为 `8`–`b` 的 UUID | `f347f599-7ca9-4860-a840-e4c92ae9a85b` |
| AO Span 记录的 External Id | OTel span ID（16 位十六进制） | `38920a6bb028a3c5` |
| AO Session | 由 `gen_ai.conversation.id` 确定性生成，与会话 ID 不相同 | — |

转换逻辑见 `observability/AoIds.java`；`/api/chat` 同时返回 `traceId` 与 `aoTraceId`。
