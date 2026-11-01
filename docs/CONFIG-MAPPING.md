# Golden Demo → bank-agent-java 配置映射

本文件只记录**配置键名、来源位置与用途**，不包含任何真实值。真实值仅保存在本机仓库外的私有文件中。

- 原项目（只读审阅，未修改）：`/Users/hangwe/Library/CloudStorage/OneDrive-Cisco/dev/splunk-ao-golden-demo`
- Phase 1 私有文件：`~/.config/bank-agent-java/application-secrets.properties`（目录 `0700`，文件 `0600`）
- Phase 2/3 预留私有文件：`~/.config/bank-agent-java/galileo.future.properties`（`0600`，首行 `RESERVED_FOR_PHASE_2_3=true`）
- 仓库内模板（占位符，无真实值）：`config/application-secrets.example.properties`、`config/galileo.future.example.properties`

## 1. Phase 1 使用的配置

| Golden Demo 来源文件 | 来源键 | 本项目私有文件 | 本项目键 | 用途 | 阶段 |
|---|---|---|---|---|---|
| `.streamlit/secrets.toml` | `openai_api_key` | `application-secrets.properties` | `OPENAI_API_KEY` | OpenAI 官方 API 密钥（已通过真实请求确认属于 OpenAI 官方 API，而非 Azure/代理） | Phase 1 使用 |
| `.streamlit/secrets.toml` | `openai_default_chat_model` | `application-secrets.properties` | `OPENAI_MODEL` | 默认模型；按用户要求使用低成本 `gpt-5-nano` | Phase 1 使用 |
| （无，OpenAI 官方默认） | — | `application-secrets.properties` | `OPENAI_BASE_URL` | `https://api.openai.com/v1` | Phase 1 使用 |
| （无） | — | 可选：私有文件或环境变量 | `OPENAI_REASONING_EFFORT`、`OPENAI_MAX_COMPLETION_TOKENS`、`OPENAI_TIMEOUT`、`OPENAI_MAX_RETRIES` | 模型推理强度与成本/超时控制，默认值见 `src/main/resources/application.yml` | Phase 1 使用 |

读取方式：`application.yml` 中 `spring.config.import: optional:file:${BANK_AGENT_SECRETS_FILE:~/.config/bank-agent-java/application-secrets.properties}`。同名环境变量优先。缺少密钥时应用可启动，但聊天接口返回 `503 LLM_NOT_CONFIGURED`。

## 2. Phase 2/3 预留配置（`RESERVED_FOR_PHASE_2_3`）

Phase 1 代码**不读取、不使用、不发送**以下任何值；项目中也没有任何 Galileo / OpenTelemetry 依赖。

| Golden Demo 来源文件 | 来源键 | 预留私有文件键 | 用途（后续阶段） | 状态 |
|---|---|---|---|---|
| `.streamlit/secrets.toml` | `backend_profile` | `AO_BACKEND_PROFILE` | 可观测性后端配置档 | 已从原项目复制 |
| `.streamlit/secrets.toml` | `galileo_console_url` | `AO_CONSOLE_URL` | Galileo / Splunk AO 控制台地址 | 已从原项目复制 |
| `.streamlit/secrets.toml` | `galileo_api_key` | `AO_API_KEY` | Galileo API 密钥（机密） | 已从原项目复制 |
| `domains/bank/config.yaml` | `galileo.project` | `AO_PROJECT_NAME` | 项目名 | 已从原项目复制 |
| `domains/bank/config.yaml` | `galileo.log_stream` | `AO_LOG_STREAM` | 日志流 | 已从原项目复制 |
| `.streamlit/secrets.toml` | `agent_control_agent_name` | `AGENT_CONTROL_AGENT_NAME` | Agent Control 中的 Agent 名称 | 已从原项目复制 |
| `.streamlit/secrets.toml` | `agent_control_api_key_header` | `AGENT_CONTROL_API_KEY_HEADER` | HTTP 头名称（非凭据） | 已从原项目复制 |
| `setup_env.py`（按约定推导） | `GALILEO_API_URL` | `AO_API_URL` | Galileo API 地址 | **推导值，未验证**，Phase 2 前需确认 |
| `setup_env.py`（按约定推导） | `AGENT_CONTROL_URL` | `AGENT_CONTROL_URL` | Agent Control 地址 | **推导值，未验证**，Phase 2 前需确认 |
| （原项目中不存在） | — | `AO_OTLP_ENDPOINT` | OTLP 导出端点 | **未配置**，Phase 2 验证后再确定，不猜测 |

## 3. 未复制的 Golden Demo 配置

以下键与本项目无关，**未复制**：`postgres_*`（数据库）、embedding 相关、`ollama*`、`bedrock*`、`environment` 等。原项目的 `.streamlit/config.toml`、`helpers/llm_utils.py` 仅用于确认模型与端点用法。

## 4. 安全措施

- 私有文件位于仓库与 OneDrive 同步目录之外；仓库 `.gitignore` 忽略 `.env*`、`*secrets*`（模板除外）、`config/*.properties`（模板除外）、日志、数据库与 `target/`。
- 脚本（`scripts/preflight.sh`）只报告"密钥是否存在"（布尔），从不打印密钥值；`/api/status` 只返回 `llmConfigured: true/false`。
- 日志通过 `SecretMasker` 遮蔽 `sk-...`、`Bearer ...` 等模式；默认关闭 OpenAI 请求/响应全文日志。
- 离线测试强制 `OPENAI_API_KEY=""` 并把 `BANK_AGENT_SECRETS_FILE` 指向不存在的文件，确保测试不读取私有配置、不调用真实 API。
- Golden Demo 原文件在开始前记录了 SHA-256 基线，结束时复核未变（见 `docs/PHASE1-VALIDATION.md`，A09）。
