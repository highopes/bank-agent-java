# Phase 1 验收记录（PHASE1-VALIDATION）

- 项目：`bank-agent-java`（`/Users/hangwe/Library/CloudStorage/OneDrive-Cisco/dev/bank-agent-java`）
- 验收日期：2026-11-01（Asia/Shanghai）
- 环境：macOS 27.0.1（arm64），Temurin OpenJDK 21+35-LTS（SDKMAN `21-tem`），Maven Wrapper → Maven 3.9.16
- 版本：Spring Boot 3.5.16，LangChain4j 1.23.0，H2 2.3.232
- 模型：`gpt-5-nano`（OpenAI 官方 API，实际返回 `gpt-5-nano-2025-08-07`），`reasoning_effort=low`
- 本文档不包含任何密钥或私有配置值

## 1. 执行过的命令

```bash
scripts/preflight.sh                    # 环境与私有配置检查（不打印密钥）
scripts/test.sh                         # ./mvnw -B clean verify：编译 + 单元测试 + 集成测试 + 打包
scripts/reset-demo.sh --yes             # 应用未运行：删除本地 H2；运行中：调用本机 reset API
scripts/run.sh --background             # 后台启动并等待健康检查
curl -s http://127.0.0.1:8080/actuator/health
curl -s http://127.0.0.1:8080/api/status
curl -s -X POST http://127.0.0.1:8080/api/chat -H 'Content-Type: application/json' -d '{"message":"..."}'
curl -s -X POST http://127.0.0.1:8080/api/payments/drafts/<draftId>/confirm -H 'Content-Type: application/json' -d '{"conversationId":"..."}'
scripts/stop.sh
./mvnw -B dependency:tree               # 检查无 OpenTelemetry / Galileo 依赖
shasum -a 256 -c <baseline>             # 在 Golden Demo 目录核对原文件未变
```

## 2. 离线自动化测试（`scripts/test.sh` = `./mvnw -B clean verify`）

结果：**BUILD SUCCESS，49 个测试，0 失败，0 错误，0 跳过**。

- Surefire 单元测试：15 个（`AmountMentionsTest`、`AmountParserTest`、`SupervisorAgentTest`、`DemoControllerLoopbackTest`、`LlmSafetyUnitTest`）
- Failsafe 集成测试：34 个（`BankingServicesIT`、`PaymentServiceIT`、`LlmIntentRouterIT`、`AgentFlowIT`、`ApiControllersIT`）

测试隔离：`OPENAI_API_KEY=""`、`BANK_AGENT_SECRETS_FILE=/nonexistent/...`、内存 H2、固定时钟；`ScriptedChatModel` 只替换 OpenAI 网络调用，LangChain4j AiServices、`@Tool` 方法、Service 与 H2 都是真实执行。

§7.1 必测项对应关系：

| §7.1 要求 | 覆盖测试 |
|---|---|
| 编译与单元测试成功，Java 21 可运行 | `clean verify` 全部通过 |
| 余额读取、交易查询、消费聚合数值正确 | `BankingServicesIT`（上月消费 10,689.00、购物 4,878.00、排除转账 1,000.00；倒序；月初凌晨无未来交易） |
| Supervisor 单域、跨域、澄清/无效输入 | `SupervisorAgentTest`、`LlmIntentRouterIT` |
| Tool 调用执行真实 Service；异常路径安全可解释 | `AgentFlowIT`（工具 → H2）、`SupervisorAgentTest`（Agent 异常记录为 FAILED）、`ApiControllersIT`（LLM 失败 502 无堆栈） |
| 多轮会话隔离、同会话延续 | `AgentFlowIT`（记忆延续与跨会话隔离）、`ApiControllersIT`（过期会话自动新建） |
| 草稿生成后确认前余额不变 | `PaymentServiceIT`、`AgentFlowIT` |
| 确认后余额/交易/审计一致；重复确认无重复扣款 | `PaymentServiceIT`、`ApiControllersIT` |
| 未登记收款人、余额不足、非法金额、过期、跨会话、取消后确认均拒绝 | `PaymentServiceIT`、`ApiControllersIT`（403/404/409） |
| 健康检查 | `ApiControllersIT`（`/actuator/health`） |
| UI Controller 基础测试 | `ApiControllersIT`（`GET /` 渲染、无密钥）、`DemoControllerLoopbackTest` |

## 3. 真实 OpenAI 在线验收（§7.2）

条件：使用从 Golden Demo 复用的 OpenAI 官方 API 凭据（仅存放于私有文件），真实 HTTP API 调用，**未使用 Stub**。证据取自 `/api/chat` 响应中的真实执行记录（`selectedAgents`、`toolCalls`、`llmCalls` 中的模型名与 token 用量），由后端 `ExecutionTrace` 与 LangChain4j `ChatModelListener` 采集。

### 3.1 九个演示问题（同一会话，2026-11-01 约 14:00）

| # | 问题 | 路由 Agent | 实际 Tool | 耗时 ms | LLM 调用 | Token 入/出 | 预期 | 实际 | 结论 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 我现在的活期账户余额是多少？ | ACCOUNT | `getAccountBalance(CHECKING)` | 4106 | 3 | 2155/298 | 52,368.50 | 52,368.50 CNY | Passed |
| 2 | 列出我最近的五笔交易。 | TRANSACTIONS | `listRecentTransactions(5)` | 9258 | 3 | 3311/631 | 倒序 5 笔 | 与数据库一致 | Passed |
| 3 | 上个月哪类消费最多？一共多少？ | TRANSACTIONS | `summarizeSpending(LAST_MONTH)` | 6392 | 3 | 4936/627 | 购物 4,878.00 / 10,689.00 | 一致 | Passed |
| 4 | 余额和最近五笔交易一起告诉我。 | ACCOUNT + TRANSACTIONS | `getAccountBalance` + `listRecentTransactions` | 10524 | 5 | 7859/1126 | 两段式合并 | 一致 | Passed |
| 5 | 向张三转账 500 元。 | PAYMENTS | `createTransferDraft(张三, 500)` | 6519 | 3 | 4129/835 | 待确认草稿、余额不变 | PENDING_CONFIRMATION，余额不变 | Passed |
| 6 | 取消刚才的转账。 | PAYMENTS | `cancelPendingTransfer` | 5583 | 3 | 4970/562 | 取消、不扣款 | CANCELLED | Passed |
| 7 | 再给他转 300 元。 | PAYMENTS | `createTransferDraft(张三, 300)` | 5736 | 3 | 5507/567 | 识别"他"、新草稿 | 新草稿；UI 确认后 EXECUTED，余额 52,068.50 | Passed |
| 8 | 向未登记陌生收款人转账 999999 元。 | PAYMENTS | `createTransferDraft` → 拒绝 | 7067 | 3 | 6032/759 | 拒绝并说明原因 | `BENEFICIARY_NOT_REGISTERED`、`AMOUNT_EXCEEDS_LIMIT`、`INSUFFICIENT_FUNDS`，已审计，无草稿 | Passed |
| 9 | 这笔转账失败的原因是什么？ | PAYMENTS | `getRecentTransferActivity` | 6587 | 3 | 7599/859 | 依据审计解释原因 | 正确解释，未创建草稿 | Passed |

每题均真实触发 OpenAI 意图判断（Supervisor 路由调用）、领域 Agent 调用和 Tool Calling；所有 LLM 调用记录的模型名均为 `gpt-5-nano-2025-08-07`。

### 3.2 额外 API 与安全场景（真实运行）

| 场景 | 结果 | 结论 |
|---|---|---|
| 已执行草稿重复确认 | `replayed=true`，余额不再变化 | Passed |
| 取消已执行草稿 | `409 DRAFT_ALREADY_EXECUTED` | Passed |
| 用不存在的会话确认 | `404 CONVERSATION_NOT_FOUND` | Passed |
| 用另一会话确认他人草稿 | `403 DRAFT_CONVERSATION_MISMATCH`，已审计 | Passed |
| 取消后再确认 | `409 DRAFT_CANCELLED` | Passed |
| "按刚才那个金额再给李四转一次"（模型自行补出 999999） | 金额守卫拒绝 `AMOUNT_NOT_STATED_BY_USER`，已审计，无草稿 | Passed |
| 审计表 | 12 条记录，覆盖创建/拒绝/取消/确认/重放/不匹配 | Passed |

### 3.3 最终冒烟测试（文档完成后，按 README 命令执行）

`scripts/reset-demo.sh --yes` → `scripts/run.sh --background`（自动生成项目 truststore，3.3 秒启动）→ 以下检查 → `scripts/reset-demo.sh --yes`（运行中，经 API 重置）→ `scripts/stop.sh`。

| 检查 | 结果 |
|---|---|
| `GET /actuator/health` | `{"status":"UP"}` |
| `GET /` | 200，页面包含 `DEMO ACCOUNT` |
| `GET /api/status` | `llmConfigured=true`、`model=gpt-5-nano`、`reasoningEffort=low`，无密钥 |
| 我现在的活期账户余额是多少？ | ACCOUNT → `getAccountBalance`；3 次 `gpt-5-nano-2025-08-07` 调用，5,732 ms；答复 52,368.50 CNY |
| 向张三转账 500 元。→ REST 确认 → 重复确认 | PAYMENTS → `createTransferDraft`（6,693 ms）；确认后 EXECUTED，活期 51,868.50（只扣一次）；重复确认 `replayed=true` |
| 运行中重置 | 活期 52,368.50、储蓄 120,000.00，会话清空 |
| 运行日志 | 0 个 ERROR；0 处 `sk-` / `Bearer` 密钥；0 处 OpenTelemetry/OTLP/Galileo 字样；启动日志只显示 `apiKey=<set>` |

## 4. 开发与首次在线验收中发现的问题及修复

以下问题在开发和首次真实模型运行中发现，均已修复，并补充了对应的离线测试，之后重新做了在线验收（结果见第 3 节）：

| 问题 | 根因 | 修复 |
|---|---|---|
| Q8：Supervisor 重复派发同一 Agent，拒绝未写入审计 | 小模型输出重复步骤；拒绝路径未审计 | Supervisor 对同一 Agent 去重；草稿拒绝统一写入 `payment_audit`（`DRAFT_REJECTED`） |
| Q9：Supervisor 把"失败原因"问题改写成 50,000 元转账任务 | 委派任务完全依赖 LLM 改写 | 单 Agent 回合把**用户原话**作为权威输入传给 Agent；强化提示词，引导 Agent 用 `getRecentTransferActivity` 解释原因 |
| 模型可能编造金额 | LLM 推断金额 | 确定性金额守卫：草稿金额必须出现在用户本轮原话中（`AMOUNT_NOT_STATED_BY_USER`） |
| Q4：跨 Agent 回答串入旧上下文 | 多 Agent 回合给每个 Agent 整句原话 | 多 Agent 回合只给每个 Agent 自己负责的部分 |
| 种子交易在月初出现"未来"日期 | 本月交易固定日期 | 本月交易日期不晚于当前时间 |
| Java 调用 OpenAI 出现 `PKIX path building failed` | 公司网络 TLS 检查（Cisco Secure Access） | `scripts/run.sh` 生成项目专用 truststore（不修改 JDK） |

此外，最早一次任务执行因 AI 服务基础设施返回 `502 Could not relay message upstream` 而中断（与项目无关），只留下 `.gitignore` 和一个临时目录；已清理后从头重新执行。中断时没有发生任何 OpenAI 调用或模拟扣款。

## 5. 阶段验收矩阵（§7.3）

| 编号 | 要求 | 证据 | 结论 |
|---|---|---|---|
| A01 | 原有 JDK 21 未被替换 | 开始与结束时 `java -version` 都是 `openjdk version "21" … Temurin-21+35`；SDKMAN `current -> 21-tem` 未变；脚本只在自身进程 `export JAVA_HOME` | Passed |
| A02 | 可编译、测试通过 | `./mvnw -B clean verify`：49 个测试，BUILD SUCCESS | Passed |
| A03 | `scripts/run.sh` 一键启动、浏览器可访问 | 第 3.3 节：`scripts/run.sh --background` 启动，health UP，`GET /` 返回 200 且页面完整 | Passed |
| A04 | 真实 OpenAI 模型响应 | 第 3 节：`llmCalls` 中的模型名 `gpt-5-nano-2025-08-07` 与 token 用量 | Passed |
| A05 | Supervisor 动态路由 | Q1–Q9 中单域 ACCOUNT / TRANSACTIONS / PAYMENTS 与跨域 ACCOUNT+TRANSACTIONS 都由 LLM 路由决定 | Passed |
| A06 | Java Tools 读取数据库 | 余额、交易、上月聚合与种子数据/H2 一致；确认后余额与交易变化 | Passed |
| A07 | 多轮状态、跨会话隔离 | Q6/Q7/Q9 依赖上下文成功；跨会话确认 403；`AgentFlowIT` 隔离测试 | Passed |
| A08 | 先确认再写入，拒绝非法与重复 | Q5–Q8、第 3.2 节、`PaymentServiceIT`、`ApiControllersIT` | Passed |
| A09 | Golden Demo 未修改 | 开始前对 6 个读取过的文件（`.streamlit/secrets.toml`、`.streamlit/config.toml`、`.streamlit/secrets.toml.example`、`domains/bank/config.yaml`、`setup_env.py`、`helpers/llm_utils.py`）记录 SHA-256；结束时 `shasum -c` 全部 OK；`git status --porcelain` 为空 | Passed |
| A10 | 私有配置安全，无密钥进入仓库/日志/文档 | 私有文件在仓库与 OneDrive 之外（0700/0600）；对仓库（含 `target/`）、运行日志和会话笔记按真实密钥值逐字扫描，0 命中；仅测试中有虚构的 `sk-proj-ABC…` 用于验证遮蔽；`.gitignore` 规则经 `git check-ignore` 验证 | Passed |
| A11 | README 完整且可复现 | README 包含 §8 要求的全部章节；在本机按 README 命令执行了 preflight → reset → run → health/UI/chat → reset → stop | Passed（本机）；第三方从零 clone 复现见第 6 节 |
| A12 | 无 Galileo/OTel 依赖与发送逻辑 | `./mvnw dependency:tree` 中 opentelemetry/galileo 为 0 条；代码中只有一处注释提到"Phase 2 可替换为 span"；运行日志 0 处；Phase 1 不读取 `galileo.future.properties`（2026-11-02 起更名为 `galileo.properties`） | Passed |

## 6. Blocked / Not Verified

| 项目 | 状态 | 说明 |
|---|---|---|
| 第三方在另一台干净 Mac 上从零 clone 并按 README 复现 | Not Verified | 只在本机验证（JDK 21 已存在、`~/.m2` 已缓存依赖）。新机器需要按 README 6.1/6.2 准备 JDK 21 与私有配置文件 |
| 浏览器内人工点击操作的录屏/截图 | Not Verified（以 HTTP 等价验证替代） | UI 页面的渲染（`GET /`）与 UI 按钮调用的同一 REST 接口（confirm/cancel/reset）均已真实调用验证；未通过自动化浏览器逐一点击 |
| 非企业网络（无 TLS 检查）下的启动 | Not Verified | 本机处于 Cisco Secure Access TLS 检查网络；`BANK_AGENT_TRUSTSTORE=none` 路径未在无检查网络中实测 |
| 其他 OpenAI 模型（如 `gpt-5-mini`、`gpt-4.1-mini`） | Not Verified | 按用户要求只验证了低成本默认模型 `gpt-5-nano`；切换方法见 README 6.3 |
| 长时间运行、并发压力 | Not Verified | 不在 Phase 1 范围；同一会话的并发请求按设计返回 `409 CONVERSATION_BUSY`，但未做压力测试 |

没有任何必测项因凭据缺失、权限不足或网络不通而被阻塞。
