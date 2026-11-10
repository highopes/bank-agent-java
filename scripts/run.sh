#!/usr/bin/env bash
# Starts the Bank Agent with a project-local Java 21 and the private local configuration.
# Usage: scripts/run.sh [--background] [--rebuild] [--observe] [--control] [--trace-content] [--galileo-off]
#   --observe        Observe: attach the official OpenTelemetry Java agent to this JVM (traces to Splunk AO)
#   --control        CONTROL_MODE=remote-api for this run only (Agent Control enforcement at business gates)
#   --trace-content  DEMO_TRACE_CONTENT=true for this run only (fictional demo text is sent to AO)
#   --galileo-off    force both off for this run (no agent, no Control: the plain application)
# The options only affect the started JVM; galileo.properties, the JDK and JAVA_TOOL_OPTIONS are never modified.
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

BACKGROUND=false
REBUILD=""
for arg in "$@"; do
  case "$arg" in
    --background|-b) BACKGROUND=true ;;
    --rebuild) REBUILD=force ;;
    --observe) export OBSERVABILITY_ENABLED=true ;;
    --control) export CONTROL_MODE=remote-api ;;
    --trace-content) export DEMO_TRACE_CONTENT=true ;;
    --galileo-off) export OBSERVABILITY_ENABLED=false CONTROL_MODE=off ;;
    -h|--help) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "Unknown option: $arg" ;;
  esac
done

require_java21
ok "Using Java 21 for this process only: $JAVA_HOME"

if pid="$(app_pid)"; then
  die "Bank Agent is already running (pid $pid). Use scripts/stop.sh first."
fi
if port_in_use "$BANK_AGENT_PORT"; then
  die "Port $BANK_AGENT_PORT is already in use. Stop the other process or set BANK_AGENT_PORT=<port>."
fi

if [ -f "$BANK_AGENT_SECRETS_FILE" ]; then
  ok "Private config found: $BANK_AGENT_SECRETS_FILE"
else
  warn "Private config not found: $BANK_AGENT_SECRETS_FILE (copy config/application-secrets.example.properties there)."
fi
if secrets_has_api_key; then
  ok "OPENAI_API_KEY is set (value hidden)."
else
  warn "OPENAI_API_KEY is not set: the UI will start but chat requests will return LLM_NOT_CONFIGURED."
fi
MODEL="${OPENAI_MODEL:-$(secrets_value OPENAI_MODEL || true)}"
info "Model: ${MODEL:-gpt-5-nano (default)}"
OBS="$(galileo_switch OBSERVABILITY_ENABLED false)"
CTL="$(galileo_switch CONTROL_MODE off)"
CONTENT="$(galileo_switch DEMO_TRACE_CONTENT false)"
info "Galileo: OBSERVABILITY_ENABLED=$OBS, CONTROL_MODE=$CTL, DEMO_TRACE_CONTENT=$CONTENT (settings: $BANK_AGENT_GALILEO_FILE)"
if [ "$OBS" = "true" ] || [ "$CTL" = "remote-api" ]; then
  info "Galileo instrumentation is on: the application resolves the AO project/agent stream at startup and fails fast on bad settings."
fi
check_java_tool_options

ensure_jar "$REBUILD"
prepare_truststore
mkdir -p "$BANK_AGENT_DATA_DIR/h2" "$BANK_AGENT_DATA_DIR/logs" "$BANK_AGENT_DATA_DIR/run"

JVM_OPTS=(-Dfile.encoding=UTF-8)
if [ -n "$TRUSTSTORE_PATH" ]; then
  # Applies to every HTTPS client in this JVM: OpenAI, Agent Control and the OTel agent's OTLP exporter.
  JVM_OPTS+=("-Djavax.net.ssl.trustStore=$TRUSTSTORE_PATH" -Djavax.net.ssl.trustStoreType=PKCS12
             "-Djavax.net.ssl.trustStorePassword=$BANK_AGENT_TRUSTSTORE_PASSWORD")
  info "TLS trust store (JDK cacerts + macOS keychain CAs): $TRUSTSTORE_PATH"
fi
if [ "$OBS" = "true" ]; then
  otel_agent_setup
  JVM_OPTS+=("${OTEL_JVM_OPTS[@]}")
  ok "Observe: OpenTelemetry Java agent $OTEL_JAVAAGENT_VERSION (SHA-256 verified) -> ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:-?} (settings: config/otel-javaagent.properties + derived OTEL_* variables; token hidden)"
else
  info "Observe: off - the OpenTelemetry Java agent is not attached (no spans, no telemetry traffic)."
fi
# Optional extra JVM flags, intentionally word-split (e.g. BANK_AGENT_JAVA_OPTS="-Xmx512m").
# shellcheck disable=SC2206
[ -n "${BANK_AGENT_JAVA_OPTS:-}" ] && JVM_OPTS+=(${BANK_AGENT_JAVA_OPTS})
URL="http://$BANK_AGENT_HOST:$BANK_AGENT_PORT/"

if [ "$BACKGROUND" = true ]; then
  info "Starting in background; log: $BANK_AGENT_LOG_FILE"
  nohup "$JAVA_HOME/bin/java" "${JVM_OPTS[@]}" -jar "$BANK_AGENT_JAR" >"$BANK_AGENT_LOG_FILE" 2>&1 &
  echo $! >"$BANK_AGENT_PID_FILE"
  # Readiness = the JVM listens on the port (Spring Boot opens it when startup is complete). No HTTP probe, so
  # starting with --observe does not add health-check traces to AO.
  for _ in $(seq 1 60); do
    if lsof -nP -a -p "$(cat "$BANK_AGENT_PID_FILE")" -iTCP:"$BANK_AGENT_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
      ok "Bank Agent is up: $URL (pid $(cat "$BANK_AGENT_PID_FILE")). Stop with scripts/stop.sh"
      exit 0
    fi
    if ! kill -0 "$(cat "$BANK_AGENT_PID_FILE")" 2>/dev/null; then
      rm -f "$BANK_AGENT_PID_FILE"
      fail "Application exited during startup. Last log lines:"
      tail -n 30 "$BANK_AGENT_LOG_FILE" >&2
      exit 1
    fi
    sleep 1
  done
  die "Application did not become healthy within 60s. See $BANK_AGENT_LOG_FILE"
fi

info "Starting in foreground at $URL (Ctrl+C to stop)"
exec "$JAVA_HOME/bin/java" "${JVM_OPTS[@]}" -jar "$BANK_AGENT_JAR"
