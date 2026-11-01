#!/usr/bin/env bash
# Starts the Bank Agent (Phase 1) with a project-local Java 21 and the private local configuration.
# Usage: scripts/run.sh [--background] [--rebuild]
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

BACKGROUND=false
REBUILD=""
for arg in "$@"; do
  case "$arg" in
    --background|-b) BACKGROUND=true ;;
    --rebuild) REBUILD=force ;;
    -h|--help) echo "Usage: scripts/run.sh [--background] [--rebuild]"; exit 0 ;;
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

ensure_jar "$REBUILD"
prepare_truststore
mkdir -p "$BANK_AGENT_DATA_DIR/h2" "$BANK_AGENT_DATA_DIR/logs" "$BANK_AGENT_DATA_DIR/run"

JVM_OPTS=(-Dfile.encoding=UTF-8)
if [ -n "$TRUSTSTORE_PATH" ]; then
  JVM_OPTS+=("-Djavax.net.ssl.trustStore=$TRUSTSTORE_PATH" -Djavax.net.ssl.trustStoreType=PKCS12
             "-Djavax.net.ssl.trustStorePassword=$BANK_AGENT_TRUSTSTORE_PASSWORD")
  info "TLS trust store (JDK cacerts + macOS keychain CAs): $TRUSTSTORE_PATH"
fi
# Optional extra JVM flags, intentionally word-split (e.g. BANK_AGENT_JAVA_OPTS="-Xmx512m").
# shellcheck disable=SC2206
[ -n "${BANK_AGENT_JAVA_OPTS:-}" ] && JVM_OPTS+=(${BANK_AGENT_JAVA_OPTS})
URL="http://$BANK_AGENT_HOST:$BANK_AGENT_PORT/"

if [ "$BACKGROUND" = true ]; then
  info "Starting in background; log: $BANK_AGENT_LOG_FILE"
  nohup "$JAVA_HOME/bin/java" "${JVM_OPTS[@]}" -jar "$BANK_AGENT_JAR" >"$BANK_AGENT_LOG_FILE" 2>&1 &
  echo $! >"$BANK_AGENT_PID_FILE"
  for _ in $(seq 1 60); do
    if curl -fsS -m 2 "http://$BANK_AGENT_HOST:$BANK_AGENT_PORT/actuator/health" >/dev/null 2>&1; then
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
