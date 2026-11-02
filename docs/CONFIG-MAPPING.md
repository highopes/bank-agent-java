# Golden Demo → bank-agent-java 配置映射

本文件只记录**配置键名、来源位置与用途**，不包含任何真实值。真实值仅保存在本机仓库外的私有文件中。

- 原项目（只读审阅，未修改）：`/Users/hangwe/Library/CloudStorage/OneDrive-Cisco/dev/splunk-ao-golden-demo`
- Phase 1 私有文件：`~/.config/bank-agent-java/application-secrets.properties`（目录 `0700`，文件 `0600`）
- Phase 2/3 私有文件：`~/.config/bank-agent-java/galileo.properties`（`0600`）。2026-11-02 由原 `galileo.future.properties` 更名，默认后端改为 Splunk Observability Cloud SaaS，同时支持两种 Galileo 后端；旧的 splunkse 版本备份为同目录 `galileo.properties.phase1-splunkse.bak`（仅作留档，应用不读取）
- 仓库内模板（占位符，无真实值）：`config/application-secrets.example.properties`、`config/galileo.example.properties`（与私有文件结构一致）

## 1. Phase 1 使用的配置

| Golden Demo 来源文件 | 来源键 | 本项目私有文件 | 本项目键 | 用途 | 阶段 |
|---|---|---|---|---|---|
| `.streamlit/secrets.toml` | `openai_api_key` | `application-secrets.properties` | `OPENAI_API_KEY` | OpenAI 官方 API 密钥（已通过真实请求确认属于 OpenAI 官方 API，而非 Azure/代理） | Phase 1 使用 |
| `.streamlit/secrets.toml` | `openai_default_chat_model` | `application-secrets.properties` | `OPENAI_MODEL` | 默认模型；按用户要求使用低成本 `gpt-5-nano` | Phase 1 使用 |
| （无，OpenAI 官方默认） | — | `application-secrets.properties` | `OPENAI_BASE_URL` | `https://api.openai.com/v1` | Phase 1 使用 |
| （无） | — | 可选：私有文件或环境变量 | `OPENAI_REASONING_EFFORT`、`OPENAI_MAX_COMPLETION_TOKENS`、`OPENAI_TIMEOUT`、`OPENAI_MAX_RETRIES` | 模型推理强度与成本/超时控制，默认值见 `src/main/resources/application.yml` | Phase 1 使用 |

读取方式：`application.yml` 中 `spring.config.import: optional:file:${BANK_AGENT_SECRETS_FILE:~/.config/bank-agent-java/application-secrets.properties}`。同名环境变量优先。缺少密钥时应用可启动，但聊天接口返回 `503 LLM_NOT_CONFIGURED`。

## 2. Phase 2/3 配置（`galileo.properties`）

Phase 1 代码**不读取、不使用、不发送**以下任何值；项目中也没有任何 Galileo / OpenTelemetry 依赖。推导规则、自动创建等逻辑由 Phase 2 实现。

**配置原则：凡是能按规则推导或能通过 API 获取的值，都不需要用户填写。** 用户只需在第 1 组中为**一种**后端填写凭据（1–2 项）；第 2 组已有可用默认值；第 3 组保留键名但值留空，留空即按当前后端的“推导规则”生成，只有需要覆盖时才填写。同名环境变量优先于文件。

### 2.1 支持的三种后端（配置档 `AO_BACKEND_PROFILE`）

| 选项 | 后端 | Console | 配置档 | 用户需填写 |
|---|---|---|---|---|
| A（当前默认） | Splunk Observability Cloud 集成的 Splunk AO SaaS | `https://app.<realm>.observability.splunkcloud.com/`（本机 realm `au0`） | `o11y` | `AO_REALM`、`AO_O11Y_TOKEN` |
| B | Galileo SaaS（传统） | `https://app.galileo.ai/` | `galileo` | `AO_API_KEY`（`AO_CONSOLE_URL` 可留空，默认即 `https://app.galileo.ai`） |
| C | Galileo 多租户云（Golden Demo 使用的后端） | `https://console.multitenant.galileocloud.io/<tenant>`（如 `splunkse`） | `galileo` | `AO_API_KEY`、`AO_CONSOLE_URL=https://console.multitenant.galileocloud.io` |

- 两类后端的凭据不通用：选项 A 使用 Splunk O11y Access Token（请求头 `X-SF-Token`）；选项 B/C 使用 Galileo API Key（请求头 `Galileo-API-Key`）。
- 选项 C 的 `AO_CONSOLE_URL` 只填 Console **根地址**；`/<tenant>` 只是浏览器登录入口，不参与任何 API 地址推导（与 Golden Demo `setup_env.py` 一致）。
- **切换后端**：填写新选项的键，并把第 3 组的 `AO_BACKEND_PROFILE` 设为 `o11y` 或 `galileo`。旧选项的值可以保留，配置档显式指定后会被忽略。第 3 组键继续留空，地址和请求头会随配置档自动变化；第 2 组的 Project / Agent Stream 会在新后端上自动创建。
- `AO_BACKEND_PROFILE` 留空时自动判断：只填了选项 A 的凭据 → `o11y`；只填了 `AO_API_KEY` → `galileo`；两者都填 → 启动报错，要求显式指定。

来源缩写：**Docs** = Splunk AO 文档 [Find Your Organization Keys（SaaS）](https://agent-observability-docs.splunk.com/references/faqs/find-keys)、[Distributed Tracing with OpenTelemetry](https://agent-observability-docs.splunk.com/sdk-api/logging/distributed-tracing-otel)、[Initialize and configure Agent Control](https://agent-observability-docs.splunk.com/how-to-guides/agent-control/initialize-and-configure-agent-control)；**SDK** = [splunk/splunk-ao-python](https://github.com/splunk/splunk-ao-python) `f7a20ed`（2026-10-30）的 `deployment.py`、`config.py`、`exporter/`、`agent_control.py`、`logger/logger.py`；**G-Docs** = Galileo 文档 [Distributed Tracing with OpenTelemetry](https://docs.galileo.ai/sdk-api/logging/distributed-tracing-otel)、[Initialize and configure Agent Control](https://docs.galileo.ai/how-to-guides/agent-control/initialize-and-configure-agent-control)、[公开 OpenAPI](https://api.galileo.ai/public/v2/openapi.json)；**G-SDK** = `galileo` 2.6.0 / `galileo-core` 4.5.0（Golden Demo 虚拟环境中已安装的版本，`galileo/otel.py`、`galileo_core/schemas/base_config.py`）；**GD** = Golden Demo `setup_env.py` 及其验收记录。

### 2.2 第 1 组：后端与凭据（只填一种后端）

| 键 | 用于 | 说明 | Golden Demo 对应 |
|---|---|---|---|
| `AO_REALM` | A | Console 地址中的 `<realm>`（官方变量 `SPLUNK_AO_REALM`，也可在 My Profile > Organizations 查看）；本机为 `au0` | — |
| `AO_O11Y_TOKEN` | A | **机密**。O11y 管理员在 Settings > Access tokens 创建，勾选 INGEST token 与 API token（角色 `agent_observability_admin`）；作为 `X-SF-Token` 的值（官方变量 `SPLUNK_AO_O11Y_TOKEN`） | — |
| `AO_CONSOLE_URL` | B/C | Console 根地址。A：留空（由 realm 推导）；B：留空即 `https://app.galileo.ai`（G-SDK 默认值）；C：`https://console.multitenant.galileocloud.io` | `galileo_console_url` |
| `AO_API_KEY` | B/C | **机密**。Galileo API Key（Galileo Console 用户菜单 > API keys），作为 `Galileo-API-Key` 的值；对选项 A 无效 | `galileo_api_key`（属于选项 C 的 splunkse 租户） |

### 2.3 第 2 组：开关与名称（已有默认值）

| 键 | 默认值 | 说明 |
|---|---|---|
| `OBSERVABILITY_ENABLED` | `false` | OTel 总开关；P0 Gate O1 通过后再开 |
| `CONTROL_MODE` | `off` | `off` / `remote-api`；与 OTel 开关相互独立 |
| `DEMO_TRACE_CONTENT` | `false` | 明确开启后才向后端发送虚构的输入/输出文本 |
| `AO_PROJECT_NAME` / `AO_AGENT_STREAM` | `bank-agent-java` / `bank-java-demo` | 名称区分大小写，与 Golden Demo 隔离（Galileo 中称 Log Stream）。**不配置任何 ID**：应用启动时通过当前后端的 REST API 按名称查找，不存在则创建，并在内存中保存 UUID（与官方 SDK Logger 行为一致） |

### 2.4 第 3 组：可选覆盖（留空 = 按当前配置档推导）

| 键 | `o11y`（选项 A）留空时 | `galileo`（选项 B/C）留空时 | 来源 |
|---|---|---|---|
| `AO_BACKEND_PROFILE` | 自动判断，见 2.1 | 自动判断，见 2.1 | SDK `DeploymentMode` / 本项目 |
| `AO_API_URL` | `https://app.${AO_REALM}.observability.splunkcloud.com/ao/api/`（SDK `O11yApiClient`：`app` 主机 + `/ao/api` + `X-SF-Token`，无 JWT 交换）。My Profile 中的 API Endpoint `https://api.${AO_REALM}…` 是 O11y 通用 REST API（`/v2/...`），实测也代理 `/ao/api/`，为与 SDK 一致使用 `app` 主机 | `app.galileo.ai` → `https://api.galileo.ai`；其他 Console 把 `console` 替换为 `api`，如 `https://api.multitenant.galileocloud.io` | SDK / G-SDK `set_api_url` |
| `AO_O11Y_API_TOKEN` | 使用 `AO_O11Y_TOKEN`（机密，可选的专用 API Token） | 不使用 | Docs / SDK |
| `AO_OTLP_ENDPOINT` | `https://ingest.${AO_REALM}.observability.splunkcloud.com/v2/trace/otlp` | `${AO_API_URL}/otel/traces`，如 `https://api.galileo.ai/otel/traces` | SDK / G-Docs |
| `AO_OTLP_AUTH_HEADER` | `X-SF-Token`（值为 `AO_O11Y_TOKEN`） | `Galileo-API-Key`（值为 `AO_API_KEY`） | SDK / G-SDK `otel.py` |
| `OTEL_SERVICE_NAME` | `bank-agent-java` | 同左 | 本项目 |
| `AGENT_CONTROL_URL` | `https://app.${AO_REALM}.observability.splunkcloud.com/ao/agent-control` | `${AO_CONSOLE_URL}/api/agent-control`，如 `https://app.galileo.ai/api/agent-control`、`https://console.multitenant.galileocloud.io/api/agent-control`（GD 实测）。Galileo 专属环境若提供 `https://agent-control.<env>.galileo.ai`（G-Docs），在此覆盖 | Docs / GD / G-Docs |
| `AGENT_CONTROL_EVALUATION_PATH` | P0 验证出的路由，作为应用内置默认值 | 同左 | P0 |
| `AGENT_CONTROL_API_KEY_HEADER` | `X-SF-Token` | `Galileo-API-Key`（GD 实测；G-Docs） | Docs / G-Docs |
| `AGENT_CONTROL_API_KEY` | 使用 `AO_O11Y_TOKEN` | 使用 `AO_API_KEY` | Docs / GD |
| `AGENT_CONTROL_AGENT_NAME` | `bank-agent-java`；若需注册 Agent，由应用自动注册或查找 | 同左 | 本项目 |
| `AGENT_CONTROL_TARGET_TYPE` | `log_stream` | `log_stream` | Docs / G-Docs / SDK |
| `AGENT_CONTROL_RUNTIME_AUTH_MODE` / `AGENT_CONTROL_RUNTIME_TOKEN_HEADER` | `jwt` / `X-Agent-Control-Runtime-Token`；Java 是否需要运行时 Token 交换由 P0 确定 | 同左 | Docs / G-Docs |
| `CONTROL_READ_FAILURE_MODE` / `CONTROL_WRITE_FAILURE_MODE` | `fail-open` / `fail-closed` | 同左 | Phase 2 任务书 |
| `MAX_CONTROL_STEER_RETRIES` | `2` | 同左 | Phase 2 任务书 |

### 2.5 已取消或变更的键

- `AGENT_CONTROL_TARGET_ID`：**不再是配置项**。它就是 Agent Stream 的 UUID，由应用启动时根据 `AO_PROJECT_NAME` / `AO_AGENT_STREAM` 解析（不存在则创建）得到。
- `AO_LOG_STREAM`：由 `AO_AGENT_STREAM` 取代（Splunk AO 中“Log Stream”现称“Agent Stream”，Galileo 中仍称 Log Stream，含义相同）。
- `AO_API_KEY`：仅用于 Galileo 后端（选项 B/C）；选项 A 使用 `AO_O11Y_TOKEN`。
- `RESERVED_FOR_PHASE_2_3`：文件已更名为正式的 `galileo.properties`，不再需要该标记。

### 2.6 2026-11-02 只读验证（**不等于** P0 验证）

选项 A（`au0`，使用用户 Token，未创建任何对象）：

- 无凭据时 `/ao/api/healthcheck` 与 `/ao/agent-control/health` 返回 `401`，随机 `/ao/...` 路径返回 `404`。
- 携带 `X-SF-Token`：`app` 与 `api` 两个主机的 `GET /ao/api/healthcheck`、`GET /ao/api/projects/all`、`GET /ao/agent-control/health`（Agent Control `8.15.0`）均为 `200`；向 OTLP 端点 POST 空的 protobuf 请求为 `200`（Token 具备 INGEST 权限）。
- 查找接口：`GET /ao/api/projects?project_name=<name>&type=gen_ai` 为 `200`（不存在时返回 `[]`）；`GET /ao/api/projects/{project_id}/log_streams` 为 `200`。创建接口 `POST /ao/api/projects`、`POST /ao/api/projects/{project_id}/log_streams` 取自 SDK，**未调用**。

选项 B/C（仅无凭据探测路由是否存在；Galileo Key 未使用）：

- `https://api.galileo.ai/healthcheck`、`https://api.multitenant.galileocloud.io/healthcheck` 为 `200`；两者的 `/otel/traces` 对 GET 返回 `405`（只接受 POST）。
- `https://app.galileo.ai/api/agent-control/health`、`https://console.multitenant.galileocloud.io/api/agent-control/health` 为 `200`（`healthy`，`8.15.0`）；随机路径为 `404`。选项 C 的 Agent Control 注册与 Project/Log Stream 查询已在 Golden Demo 中用真实 Key 验收（见其 `documentation/VALIDATION.md`）。
- Galileo 公开 OpenAPI 声明接受 `Galileo-API-Key` 与 `Splunk-AO-API-Key` 两种 API Key 请求头，项目接口为 `/v2/projects`、`/v2/projects/paginated`、`/v2/projects/{project_id}/log_streams`。

仍待 P0 验证：真实 Span 在 Console 中的 Project/Agent Stream 路由、创建接口、Control 评估与事件接口。

## 3. 未复制的 Golden Demo 配置

以下键与本项目无关，**未复制**：`postgres_*`（数据库）、embedding 相关、`ollama*`、`bedrock*`、`environment` 等。原项目的 `.streamlit/config.toml`、`helpers/llm_utils.py` 仅用于确认模型与端点用法。

## 4. 安全措施

- 私有文件位于仓库与 OneDrive 同步目录之外；仓库 `.gitignore` 忽略 `.env*`、`*secrets*`（模板除外）、`config/*.properties`（模板除外）、日志、数据库与 `target/`。
- 脚本（`scripts/preflight.sh`）只报告"密钥是否存在"（布尔），从不打印密钥值；`/api/status` 只返回 `llmConfigured: true/false`。
- 日志通过 `SecretMasker` 遮蔽 `sk-...`、`Bearer ...` 等模式；默认关闭 OpenAI 请求/响应全文日志。
- 离线测试强制 `OPENAI_API_KEY=""` 并把 `BANK_AGENT_SECRETS_FILE` 指向不存在的文件，确保测试不读取私有配置、不调用真实 API。
- Golden Demo 原文件在开始前记录了 SHA-256 基线，结束时复核未变（见 `docs/PHASE1-VALIDATION.md`，A09）。
