# Galileo 插桩的协议验证（Protocol Verification）

> 第 1–8 节是 **2026-11-02 的历史验证记录**（当时的实现由应用自建 OTel SDK 发送），结论保持原样、未作修改。2026-11-09 插桩重构为“OTel 官方 Java Agent + LangChain4j 监听”之后的复核见**第 9 节**。

在写 Java 插桩代码之前，先用最小的 Java 探针确认 Splunk AO 的 OTLP 导入、Project/Agent Stream 接口和 Agent Control 接口在真实 SaaS 上怎样工作，避免按猜测实现。本文记录这些验证的方法和结论。

> 执行日期：2026-11-02。后端：Splunk AO SaaS（Splunk Observability Cloud，realm `au0`），Agent Control 服务版本 8.15.0。所有请求都由本机的 Java 探针或 Java 应用发出（`tools/protocol-probes/`）；请求与响应示例均已脱敏，不含 Token。Galileo 两种后端（选项 B/C）**未做协议验证**。

## 1. 结论（检查项）

| 检查项 | 验证内容 | 方法 | 结果 |
|---|---|---|---|
| **O1** OTLP 路由与解析 | Java OTel SDK 发出的 OTLP/HTTP Span 能进入指定的 Project / Agent Stream，并被解析为 agent / llm / tool / control 类型 | `tools/protocol-probes/OtlpProbe.java` 发送合成 Trace，再用 AO REST API 与 Console 回读 | **PASS** |
| **C1** Control API 可用 | 健康检查、Agent 注册（步骤目录 + 目标 Agent Stream）、Runtime Token 交换、评估接口、绑定规则查询 | `tools/protocol-probes/ControlProbe.java`，真实规则 13/14/15 | **PASS** |
| **C2** Java 端执行门禁 | 服务端只返回决策，PRE 检查时的阻止必须由 Java 实现 | Java Adapter + 离线测试 + 在线 C-B/C-E | **PASS**（服务端不会替 Java 抛异常，见 [`AGENT-CONTROL.md`](AGENT-CONTROL.md)） |
| **C3** Console Controls 标签 | Java 发出的 control span 在 Console 中显示官方 Control 详情，并与同一 Trace 的被检查步骤关联 | OtlpProbe 发送带 `galileo.span.kind=control` 的 Span，在 Console 中查看 | **PASS**（之后用真实应用 Trace 再次验证，见 [`VALIDATION.md`](VALIDATION.md) 第 5 节） |

关键发现：官方 `splunk-ao-python` SDK（`f7a20ed`，`converter/attribute_mapping.py`、`converter/span_converter.py`）在 o11y 模式下把所有 AO Span 类型（包括 control）**转换为普通 OTLP Span** 发送，`handlers/agent_control/bridge.py` 把 Agent Control 的评估结果转成 control span。因此 Java 只要发出相同的 OTLP 属性，就能走官方 Controls 标签的数据通道，不需要 Python sidecar，也不需要调用非公开接口。

## 2. Project / Agent Stream：查找或创建

| 请求 | 结果 |
|---|---|
| `GET /ao/api/projects?project_name=bank-agent-java&type=gen_ai` | 200；不存在时 `[]` |
| `POST /ao/api/projects` `{"name":"bank-agent-java","type":"gen_ai",…}` | 200，返回 `id`（`7f8db462-…`） |
| 重复 POST 同名 Project | **422** `{"detail":"A project with this (name, type) already exists."}` |
| `GET /ao/api/projects/{project_id}/log_streams` | 200，列表 |
| `POST /ao/api/projects/{project_id}/log_streams` `{"name":"bank-java-demo"}` | 200，返回 `id`（`3bc831a3-…`） |
| 重复 POST 同名 Stream | **400** `data_validation_error`，`error_code 10500`，"Log stream … already exists" |

应用启动时按这个顺序执行（`AoTargetResolver`）：先查找，不存在才创建；创建时如果遇到 422/400（并发创建），重新查找一次。UUID 只保存在内存中，不写入配置文件。

## 3. OTLP（Gate O1）

| 项目 | 验证结果 |
|---|---|
| 端点 | `https://ingest.au0.observability.splunkcloud.com/v2/trace/otlp`（OTLP/HTTP protobuf；**不是**标准的 `/v1/traces`） |
| 认证 | 请求头 `X-SF-Token: <O11y access token>` |
| 路由 | 请求头 `project: <Project 名称>`、`logstream: <Agent Stream 名称>`，同时设置 Resource 属性 `splunk_ao.project.name`、`splunk_ao.logstream.name`（与官方 SDK 相同） |
| 错误路由 | 使用 `agentstream` 请求头 / `splunk_ao.agentstream.name` 时，Span 进入自动创建的 “Unassigned” Stream → **必须用 `logstream`** |
| Span 类型解析 | `gen_ai.operation.name` = `invoke_agent` / `chat` / `execute_tool` 分别成为 agent / llm / tool；`galileo.span.kind=control` + `gen_ai.operation.name=control` 成为 control |
| Trace ID | AO 把 32 位十六进制 OTel trace ID 存为 UUIDv4：第 13 位强制为 `4`，第 17 位强制为 RFC 4122 变体（`8`–`b`）。例：`b8e9945f6b751f313e8d6a84fe3a2b1d` → `b8e9945f-6b75-4f31-be8d-6a84fe3a2b1d`。原始 ID 保存在记录的 External Id 中（`AoIds`） |
| Session | `splunk_ao.session.id`（无论放在 Span 还是 Resource 上）**被忽略**；AO 根据 `gen_ai.conversation.id` 确定性地生成 Session：两个 Trace 使用同一 conversation ID 会进入同一 Session。用 API 预先创建的 Session 不会被使用（保持为空） |
| 入库延迟 | 30–90 秒后才能通过 API 读到 |
| Java SDK | OpenTelemetry Java 1.49.0，JDK HttpClient sender；公司 TLS 检查环境使用项目专用 truststore（与 OpenAI 调用相同） |

AO 记录中 control span 的字段（探针 Trace `b8e9945f…`）：`type=control`、`control_id`、`agent_name`、`check_stage`、`applies_to`、`evaluator_name`、`selector_path`、`output={"action","matched","confidence"}`、`external_id=<OTel span id>`；**`input` 为空**（AO 不映射 control span 的 `gen_ai.input.messages`）。

## 4. Agent Control REST API（Gate C1）

基础地址（o11y）：`https://app.<realm>.observability.splunkcloud.com/ao/agent-control`。所有管理类请求使用 `X-SF-Token`。

| 请求 | 结果 |
|---|---|
| `GET /health` | 200 `{"status":"healthy","version":"8.15.0"}` |
| `GET /api/v1/agents/bank-agent-java`（注册前） | 404 `AGENT_NOT_FOUND`（problem+json） |
| `POST /api/v1/agents/initAgent` | 200 `{"created":true,"controls":[]…}`；再次调用 `created=false`，返回已绑定的规则 |
| `POST /api/v1/auth/runtime-token-exchange` `{"target_type":"log_stream","target_id":"<stream uuid>"}` | 200 `{"token":"<JWT, 600 字符>","expires_at":"<+5 分钟>","target_type","target_id","scopes":["runtime.use"]}` |
| `POST /api/v1/evaluation`，只带 `X-SF-Token` | **401** `AUTH_MISSING_KEY` "Missing X-Agent-Control-Runtime-Token header." |
| `POST /api/v1/evaluation`，加 `X-Agent-Control-Runtime-Token: <JWT>` | 200；curl 首次约 0.76 s（新建 TLS），Java keep-alive 约 0.26 s |
| 对未注册的步骤名评估 | 200 `is_safe=true`（没有规则适用） |
| `GET /api/v1/agents/bank-agent-java/controls?target_type=log_stream&target_id=…` | 200 `{"controls":[{"id","name","control":{…}}]}` |
| `PUT /api/v1/controls` `{"name","data":{…}}` | 200 `{"control_id":13}` |
| `PUT /api/v1/control-bindings/by-key` | 200 `{"binding_id":…,"created":true,"enabled":true}` |
| `PUT /api/v1/controls/{id}/data` `{"data":{…}}` | 200 `{"success":true}`（在线验收中用于“不重启修改规则”） |

initAgent 请求（脱敏）：

```json
{
  "agent": {"agent_name": "bank-agent-java", "agent_description": "Bank Agent Java demo …", "agent_version": "0.2.0-SNAPSHOT"},
  "steps": [{"type": "llm", "name": "banking-assistant", "description": "…"},
            {"type": "tool", "name": "createTransferDraft", "description": "…"},
            {"type": "tool", "name": "payment.confirm", "description": "…"}],
  "conflict_mode": "overwrite",
  "target_type": "log_stream",
  "target_id": "<Agent Stream UUID>"
}
```

评估请求（应用实际发送的结构）：

```json
{
  "agent_name": "bank-agent-java",
  "stage": "pre",
  "target_type": "log_stream",
  "target_id": "<Agent Stream UUID>",
  "step": {
    "type": "tool",
    "name": "createTransferDraft",
    "input": {"beneficiary": "王五", "amount": "500"},
    "context": {"source": "bank-agent-java", "conversation_id": "<uuid>"}
  }
}
```

请求头：`X-SF-Token`、`X-Agent-Control-Runtime-Token`、`X-Agent-Control-SDK: bank-agent-java`、`X-Trace-Id` / `X-Span-Id`（当前 OTel 上下文，与官方 SDK 相同）。

DENY 响应（探针规则 14，原样，来自 `src/test/resources/agent-control/evaluation-deny.json`）：

```json
{"is_safe":false,"confidence":1.0,"reason":null,
 "matches":[{"control_execution_id":"51f59d12-…","control_id":14,"control_name":"bank-java-p0-deny-probe","action":"deny",
   "result":{"matched":true,"confidence":1.0,"message":"Control triggered. Logic: any, MatchOn: match. Matched: BLOCKED-BEN",
     "metadata":{"condition_trace":{"selector_path":"input.beneficiary","evaluator_name":"list","matched":true,…}},"error":null},
   "execution_duration_ms":0.35,"steering_context":null}],
 "errors":null,"non_matches":null}
```

STEER 响应的区别：`"action":"steer"`，`"steering_context":{"message":"Answer in one short sentence and do not mention balances."}`。OBSERVE 匹配时 `is_safe=true`，`matches` 中 `action=observe`。

## 5. Console Controls 标签（Gate C3）

探针 Trace `b8e9945f6b751f313e8d6a84fe3a2b1d`：Messages 视图中 control span 显示盾牌图标；选中后右侧 **Controls 1** 标签显示控件名、Triggered、Applies To `tool_call`、Trigger if `input`、View control（链接到 `#/agent-obs/controls/13/edit`）。探针运行时的截图包含 Console 页头中的用户名/组织名，没有放入仓库；仓库中的截图来自之后真实应用产生的 Trace（[`VALIDATION.md`](VALIDATION.md) 第 5 节）。

control span 的 OTLP 属性（与官方 SDK `set_control_attributes` 相同）：

| 属性 | 示例 |
|---|---|
| Span 名称 | 规则名，如 `bank-java-deny-restricted-transfer` |
| 父 Span | 被检查的步骤（tool / llm / `payment.confirm`） |
| `galileo.span.kind` | `control` |
| `gen_ai.operation.name` | `control` |
| `agent_control.control_id` / `control_name` / `agent_name` | `18` / 规则名 / `bank-agent-java` |
| `agent_control.check_stage` / `applies_to` | `pre`/`post`；`tool_call`/`llm_call` |
| `agent_control.evaluator_name` / `selector_path` | `list` / `input.beneficiary` |
| `agent_control.action` / `matched` / `confidence` | `deny` / `true` / `1.0` |
| `agent_control.control_execution_id` | 评估响应中的 ID |
| `gen_ai.output.messages` | `{"action","matched","confidence"}`（Console 中的 Output） |

## 6. 插桩实现后补充的观察

| 观察 | 证据 | 状态 |
|---|---|---|
| OTel `ERROR` 状态、`error.type`、`splunk_ao.status_code`（官方 SDK 设置的属性）都只出现在 span metadata 中，记录的 `status_code` 保持 0 | `ErrorSpanProbe` 7 种变体；应用 Trace `d3fa7b23…`（metadata `splunk_ao.status_code: "403"`） | 已记录（K2） |
| Java 发出的部分 Trace 在 AO 中不显示输入/输出内容，同一格式的其他 Trace 显示完整 | 抓包重放、拆分请求、改 conversation ID、官方 SDK 对照（[`VALIDATION.md`](VALIDATION.md) 第 8 节） | 根因未确定（K1） |
| 评估接口同时有 `X-Trace-Id`/`X-Span-Id` 时，对 Trace 内容显示没有可见影响 | 带头评估后用相同 trace ID 重放 | 不是 K1 的原因 |

## 7. 向 Galileo / Splunk AO 团队提出的技术问题

1. **Q1（K1）**：通过 OTLP 发送、`gen_ai.input.messages` / `gen_ai.output.messages` / `gen_ai.tool.call.*` 格式与官方 SDK 一致的 Span，为什么有时内容不显示？同一 `gen_ai.conversation.id`（同一 Session）下的后续 Trace 是否有内容合并或去重规则？OTLP 导入的内容字段是否有正式的 schema 说明？
2. **Q2（K2）**：OTLP Span 的失败状态应该如何表达，才能让 AO 记录的 `status_code` 不为 0？`splunk_ao.status_code` 是否只对 SDK 内部路径生效？
3. **Q3**：Control span 的被评估内容（Input）应该用哪个属性发送，才能在 Console 中显示？
4. **Q4**：`/ao/agent-control` 的 `/api/v1/evaluation`、`/api/v1/auth/runtime-token-exchange`、`initAgent` 是否是对第三方语言（Java）正式支持的公共契约？是否有 OpenAPI 文档和版本承诺？
5. **Q5**：通过 OTLP control span 上报 Control 结果（官方 Python SDK 的 bridge 路径）是否是推荐的 Controls 标签数据通道？是否计划提供单独的 ControlExecutionEvent 上报接口？
6. **Q6**：Galileo 后端（`app.galileo.ai`、多租户云）是否使用相同的评估路由、Runtime Token 流程与 OTLP 属性（`galileo.project.name` / `galileo.logstream.name`）？

## 8. 探针与复现

```bash
tools/protocol-probes/run-probe.sh OtlpProbe       # 合成 agent/llm/tool/control Trace；打印 trace ID
tools/protocol-probes/run-probe.sh ControlProbe    # health / initAgent / runtime token / 评估；打印状态码和耗时
tools/protocol-probes/run-probe.sh ErrorSpanProbe  # 各种错误表示与 control span 变体（K2 / K1 排查）
```

脚本只在自身进程中从私有文件读取 `AO_REALM` / `AO_O11Y_TOKEN`，从不打印。探针发送的都是合成的、不含敏感信息的数据。

## 9. 2026-11-09 重构后复核

> 执行日期：2026-11-09。后端：Splunk AO SaaS（realm `au0`），Agent Control 服务版本 8.15.0。发送方变为 **OTel 官方 Java Agent 2.33.1**（应用不再含 SDK）；Control REST 客户端未修改。全部请求由本机应用发出，Token 只在进程内使用；本节只记录协议层面的事实，业务场景结果见 [`VALIDATION.md`](VALIDATION.md)。

| 检查项 | 方法 | 结果 |
|---|---|---|
| **R1** Agent 的 OTLP 请求 | 黑盒测试 `OtelJavaAgentIT`：真实 `-javaagent` + 本地 OTLP 接收器，用与 `run.sh` 相同的 `OTEL_*` 推导 | **PASS**：`POST /v2/trace/otlp`、`Content-Type: application/x-protobuf`、`X-SF-Token`、`project`、`logstream` 请求头；Resource 含 `service.name`、`splunk_ao.project.name`、`splunk_ao.logstream.name`；只有一个导出方 |
| **R2** 真实 AO 入库与路由 | `run.sh --observe` + `galileo-smoke.sh --yes`，AO REST 回读 | **PASS**：3 条 Trace 进入 Project `bank-agent-java` / Agent Stream `bank-java-demo`；公司 TLS 检查环境下，Agent 的 OTLP 导出使用项目专用 truststore（JVM 参数）正常 |
| **R3** 自动 HTTP Span 的解析 | AO 回读 | **PASS**：Java Agent 的 Tomcat SERVER Span 被解析为 `type=http`，Input = `POST /api/chat`，Output = HTTP 状态码 `200`；Trace 记录的名称、Input、Output 取自这个根节点；其下的 `invoke_agent` / `chat` / `execute_tool` 仍分别解析为 agent / llm / tool，token 与 cost 正常 |
| **R4** 只含 HTTP Span 的 Trace | 用 W3C `traceparent` 指定已知 trace ID 访问 `/api/status`、`/`、`/actuator/health`，再按 ID 查询 | 三条都在 >20 分钟后返回 **404 `no traces matched the provided ID`**（同时段的 GenAI Trace 均已入库）。即：没有 GenAI Span 的 Trace 未出现在该 Project 中；这是观察到的行为，AO 是否有意过滤未经官方确认（问题 Q7） |
| **R5** 自动 JDBC Span 的解析（可选开启） | `OTEL_INSTRUMENTATION_JDBC_ENABLED=true`（数据目录放在 `/tmp`，避免用户名进入路径），AO 回读 | SQL Span 挂在 `execute_tool` 下，解析为 **`type=workflow`**；metadata 含 `db.query.text`（参数为 `?`）、`db.namespace`、`db.connection_string`（含 H2 文件路径）。因此默认关闭 JDBC |
| **R6** Session | 同一会话两轮聊天 + UI 确认（独立 Trace） | **PASS**：三条 Trace 同一 Session；Session 仍由 `gen_ai.conversation.id` 决定（根节点是 HTTP Span 不影响） |
| **R7** Span link | 检查 AO Trace 与 Span 记录返回的全部字段 | AO 记录中**没有** link 相关字段；旧实现中确认 → 草稿创建的 OTel link 在 AO 中无处呈现，重构后删除 |
| **R8** control span | `--observe --control` 下 6 条演示规则的真实场景，AO 回读 | **PASS**：每个评估结果一个 `type=control` 记录，`control_id`、`check_stage`、`action`、`matched` 正确，父节点分别是被检查的 `tool`、根 `agent`（`banking-assistant`）、`tool payment.confirm`；STEER 场景依次为 control（matched）→ 改写用的 `llm` → control（未 matched） |
| **R9** Agent Control REST | 启动注册、Runtime Token、评估、规则绑定 PATCH 停用/恢复 | **PASS**：与第 4 节一致；规则绑定变更约 10 秒内生效，应用未重启；评估请求的 `X-Trace-Id` 与当前 Tool Span 的 trace ID 一致（黑盒测试断言） |
| **R10** 内容显示（K1） | AO 回读 Input / Output | 本次多数 Trace 的 agent / llm / tool 占位符正常显示；同一 Session 中较晚的确认 Trace 的 `payment.confirm` Input 为空，与历史问题 K1 现象一致，根因仍未确定 |

**尚未验证**：Console 界面截图（本次只用 AO REST API 回读，未登录 Console 截图）；绑定在领域 Agent 模型步骤（`*-agent`）上的规则（演示规则中没有，离线测试覆盖了 LLM Span 下的 control span）；Galileo 两种后端。

新增的技术问题：

7. **Q7**：只包含非 GenAI Span（如 HTTP 健康检查）的 Trace 不出现在 Agent Stream 中，这是 AO ingest 的设计行为吗？是否有官方说明（例如按 `gen_ai.operation.name` 过滤）？
8. **Q8**：OTel Java Agent 的 HTTP SERVER Span 被解析为 `type=http` 并作为 Trace 根节点（Trace 名称为 `POST /api/chat`）。官方是否建议把 Agent 根 Span 放在 HTTP Span 之下，还是建议 Trace 根就是 Agent Span？
