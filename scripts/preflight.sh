#!/usr/bin/env bash
# Pre-flight check for the Bank Agent (Phase 1 base + Phase 2 Observe / Agent Control). Read-only: it reports
# readiness, never prints secret values and never creates anything. Exit code 0 = ready, 1 = blocking problem.
set -uo pipefail
source "$(dirname "$0")/lib/common.sh"

BLOCKERS=0
blocker() { fail "$*"; BLOCKERS=$((BLOCKERS + 1)); }

echo "${C_BOLD}== Bank Agent Java - preflight ==${C_RESET}"

# 1. Operating system
os="$(uname -s)"; arch="$(uname -m)"
if [ "$os" = "Darwin" ]; then
  ok "macOS $(sw_vers -productVersion 2>/dev/null || echo '?') ($arch)"
else
  warn "Not macOS ($os $arch). The scripts are designed for macOS; Linux may work but is not verified."
fi

# 2. Java 21 (selected for this process only; global default untouched)
global_java="$(command -v java 2>/dev/null || true)"
if [ -n "$global_java" ]; then
  info "Default 'java' on PATH: $(java -version 2>&1 | head -n 1) (left unchanged)"
fi
if select_java21; then
  ok "Java 21 for this project: $JAVA_HOME ($("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1))"
else
  blocker "Java 21 not found (checked JAVA_HOME, /usr/libexec/java_home -v 21, ~/.sdkman/candidates/java/21*, PATH)."
fi

# 3. Maven Wrapper
if [ -x "$BANK_AGENT_ROOT/mvnw" ] && [ -f "$BANK_AGENT_ROOT/.mvn/wrapper/maven-wrapper.properties" ]; then
  ok "Maven Wrapper present ($(awk -F'/' '/distributionUrl/ {print $(NF-1)}' "$BANK_AGENT_ROOT/.mvn/wrapper/maven-wrapper.properties" 2>/dev/null | sed 's/^/Maven /'))"
else
  blocker "Maven Wrapper missing or not executable: $BANK_AGENT_ROOT/mvnw"
fi

# 4. Port
if pid="$(app_pid)"; then
  info "Bank Agent already running (pid $pid) on port $BANK_AGENT_PORT."
elif port_in_use "$BANK_AGENT_PORT"; then
  blocker "Port $BANK_AGENT_PORT on $BANK_AGENT_HOST is in use by another process (set BANK_AGENT_PORT to change)."
else
  ok "Port $BANK_AGENT_PORT is free (bind address $BANK_AGENT_HOST)."
fi

# 5. Private configuration (presence only)
if [ -f "$BANK_AGENT_SECRETS_FILE" ]; then
  perms="$(stat -f '%Lp' "$BANK_AGENT_SECRETS_FILE" 2>/dev/null || stat -c '%a' "$BANK_AGENT_SECRETS_FILE" 2>/dev/null)"
  ok "Private config file exists: $BANK_AGENT_SECRETS_FILE (permissions $perms)"
  case "$perms" in 600|400) ;; *) warn "Recommended: chmod 600 \"$BANK_AGENT_SECRETS_FILE\"" ;; esac
  case "$BANK_AGENT_SECRETS_FILE" in
    "$BANK_AGENT_ROOT"/*) warn "The private config file is inside the project folder; keep it outside the repository." ;;
  esac
else
  warn "Private config file not found: $BANK_AGENT_SECRETS_FILE"
fi
if secrets_has_api_key; then
  ok "OPENAI_API_KEY: configured (value not shown)"
else
  blocker "OPENAI_API_KEY: missing (set it in $BANK_AGENT_SECRETS_FILE or the environment)."
fi
model="${OPENAI_MODEL:-$(secrets_value OPENAI_MODEL 2>/dev/null || true)}"
ok "OPENAI_MODEL: ${model:-gpt-5-nano (application default)}"
base_url="${OPENAI_BASE_URL:-$(secrets_value OPENAI_BASE_URL 2>/dev/null || true)}"
base_url="${base_url:-https://api.openai.com/v1}"
info "OPENAI_BASE_URL: $base_url"

# 6. Network reachability (unauthenticated request; 401 means reachable)
code="$(curl -sS -o /dev/null -m 10 -w '%{http_code}' "${base_url%/}/models" 2>/dev/null || true)"
case "$code" in
  401|200) ok "OpenAI endpoint reachable from this machine (HTTP $code without credentials)." ;;
  *) warn "OpenAI endpoint not reachable right now (HTTP '${code:-none}'). Online features will fail until network access works." ;;
esac

# 7. TLS inspection / trust store
host="$(printf '%s' "$base_url" | sed -E 's#^https?://([^/:]+).*#\1#')"
issuer="$(echo | openssl s_client -connect "$host:443" -servername "$host" 2>/dev/null | openssl x509 -noout -issuer 2>/dev/null || true)"
if [ -n "$issuer" ]; then
  info "TLS issuer seen for $host: ${issuer#issuer=}"
  if printf '%s' "$issuer" | grep -qi "Cisco\|Zscaler\|Netskope\|Umbrella\|Palo Alto"; then
    warn "Corporate TLS inspection detected. scripts/run.sh builds a project trust store (JDK cacerts + macOS keychain CAs) so Java can reach OpenAI."
  fi
fi
ts="${BANK_AGENT_TRUSTSTORE:-$BANK_AGENT_TRUSTSTORE_DEFAULT}"
if [ "${BANK_AGENT_TRUSTSTORE:-}" = "none" ]; then
  info "Project trust store disabled (BANK_AGENT_TRUSTSTORE=none)."
elif [ -s "$ts" ]; then
  ok "Project trust store present: $ts"
else
  info "Project trust store not built yet; scripts/run.sh will create it at $ts"
fi

# 8. Data directory (kept outside the synced project folder)
info "Data directory: $BANK_AGENT_DATA_DIR"
if [ -f "$BANK_AGENT_JAR" ]; then
  ok "Application jar built: $BANK_AGENT_JAR"
else
  info "Application jar not built yet; scripts/run.sh builds it automatically."
fi

# 9. Phase 2: Observe (OpenTelemetry) and Agent Control. Read-only: shows the profile, every final value with
#    its source ([derived] / [override] / [default]) and credentials only as <set>/<missing>; looks up the
#    project / agent stream by name but never creates anything.
echo
echo "${C_BOLD}== Phase 2 Observe / Agent Control ==${C_RESET}"
if [ -f "$BANK_AGENT_GALILEO_FILE" ]; then
  perms="$(stat -f '%Lp' "$BANK_AGENT_GALILEO_FILE" 2>/dev/null || stat -c '%a' "$BANK_AGENT_GALILEO_FILE" 2>/dev/null)"
  ok "Phase 2 private file exists: $BANK_AGENT_GALILEO_FILE (permissions $perms)"
  case "$perms" in 600|400) ;; *) warn "Recommended: chmod 600 \"$BANK_AGENT_GALILEO_FILE\"" ;; esac
else
  info "Phase 2 private file not found: $BANK_AGENT_GALILEO_FILE (Observe/Control stay off; template: config/galileo.example.properties)"
fi
OBS="$(phase2_switch OBSERVABILITY_ENABLED false)"
CTL="$(phase2_switch CONTROL_MODE off)"
if [ -n "${SELECTED_JAVA_HOME:-}" ]; then
  if (ensure_jar >/dev/null 2>&1) && prepare_truststore >/dev/null 2>&1; then
    if ! run_phase2_cli preflight; then
      if [ "$OBS" = "true" ] || [ "$CTL" = "remote-api" ]; then
        blocker "Phase 2 is switched on (OBSERVABILITY_ENABLED=$OBS, CONTROL_MODE=$CTL) but its preflight failed (see above)."
      else
        warn "Phase 2 settings have problems, but both switches are off, so the application is not affected."
      fi
    fi
  else
    warn "Could not build the application jar to check Phase 2 settings; run './mvnw -B package' for details."
  fi
fi

echo
if [ "$BLOCKERS" -eq 0 ]; then
  ok "Preflight passed. Start with: scripts/run.sh"
  exit 0
fi
fail "Preflight found $BLOCKERS blocking problem(s)."
exit 1
