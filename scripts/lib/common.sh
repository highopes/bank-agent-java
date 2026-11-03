#!/usr/bin/env bash
# Shared helpers for the bank-agent-java scripts. Source this file; do not execute it directly.
# Compatible with the macOS default bash 3.2. Nothing here changes the global Java configuration:
# JAVA_HOME / PATH are only exported into the current script process.

BANK_AGENT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BANK_AGENT_DATA_DIR="${BANK_AGENT_DATA_DIR:-$HOME/.local/share/bank-agent-java}"
BANK_AGENT_SECRETS_FILE="${BANK_AGENT_SECRETS_FILE:-$HOME/.config/bank-agent-java/application-secrets.properties}"
# Galileo instrumentation: Splunk AO / Galileo backend + Agent Control settings (private file, 0600, outside the repository).
BANK_AGENT_GALILEO_FILE="${BANK_AGENT_GALILEO_FILE:-$HOME/.config/bank-agent-java/galileo.properties}"
BANK_AGENT_HOST="${BANK_AGENT_HOST:-127.0.0.1}"
BANK_AGENT_PORT="${BANK_AGENT_PORT:-8080}"
BANK_AGENT_JAR="$BANK_AGENT_ROOT/target/bank-agent-java.jar"
BANK_AGENT_PID_FILE="$BANK_AGENT_DATA_DIR/run/bank-agent.pid"
BANK_AGENT_LOG_FILE="$BANK_AGENT_DATA_DIR/logs/bank-agent.log"
BANK_AGENT_TRUSTSTORE_DEFAULT="$BANK_AGENT_DATA_DIR/tls/truststore.p12"
# Password of a trust store that only holds public CA certificates; it is not a secret.
BANK_AGENT_TRUSTSTORE_PASSWORD="changeit"

export BANK_AGENT_DATA_DIR BANK_AGENT_SECRETS_FILE BANK_AGENT_GALILEO_FILE BANK_AGENT_HOST BANK_AGENT_PORT

if [ -t 1 ]; then
  C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_BOLD=$'\033[1m'; C_RESET=$'\033[0m'
else
  C_RED=""; C_GREEN=""; C_YELLOW=""; C_BOLD=""; C_RESET=""
fi

info() { printf '%s[INFO]%s %s\n' "$C_BOLD" "$C_RESET" "$*"; }
ok()   { printf '%s[ OK ]%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '%s[WARN]%s %s\n' "$C_YELLOW" "$C_RESET" "$*" >&2; }
fail() { printf '%s[FAIL]%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; }
die()  { fail "$*"; exit 1; }

# Prints the major version of the java binary under the given JAVA_HOME candidate (empty on error).
java_major_version() {
  local home="$1" line ver
  [ -x "$home/bin/java" ] || return 1
  line="$("$home/bin/java" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')"
  [ -n "$line" ] || return 1
  ver="${line%%.*}"
  if [ "$ver" = "1" ]; then
    ver="$(printf '%s' "$line" | cut -d. -f2)"
  fi
  printf '%s' "$ver"
}

_try_java_home() {
  local candidate="$1"
  [ -n "$candidate" ] && [ -d "$candidate" ] || return 1
  if [ "$(java_major_version "$candidate" 2>/dev/null)" = "21" ]; then
    SELECTED_JAVA_HOME="$(cd "$candidate" && pwd -P)"
    return 0
  fi
  return 1
}

# Selects a Java 21 runtime for the current process only.
# Order: JAVA_HOME, /usr/libexec/java_home -v 21, SDKMAN 21.x candidates, java on PATH.
select_java21() {
  SELECTED_JAVA_HOME=""
  local candidate path_java
  if _try_java_home "${JAVA_HOME:-}"; then :;
  elif [ -x /usr/libexec/java_home ] && candidate="$(/usr/libexec/java_home -v 21 2>/dev/null)" && _try_java_home "$candidate"; then :;
  else
    for candidate in "$HOME"/.sdkman/candidates/java/21*; do
      if _try_java_home "$candidate"; then break; fi
    done
    if [ -z "$SELECTED_JAVA_HOME" ] && path_java="$(command -v java 2>/dev/null)"; then
      candidate="$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.home =/ {print $2; exit}')"
      _try_java_home "$candidate" || true
    fi
  fi
  if [ -z "$SELECTED_JAVA_HOME" ]; then
    return 1
  fi
  export JAVA_HOME="$SELECTED_JAVA_HOME"
  export PATH="$JAVA_HOME/bin:$PATH"
  return 0
}

require_java21() {
  if ! select_java21; then
    die "Java 21 not found. Install a JDK 21 (e.g. 'sdk install java 21-tem' or Temurin 21) or set JAVA_HOME to a JDK 21. The global default Java is not changed by these scripts."
  fi
}

port_in_use() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

app_pid() {
  local pid
  [ -f "$BANK_AGENT_PID_FILE" ] || return 1
  pid="$(cat "$BANK_AGENT_PID_FILE" 2>/dev/null)"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null || return 1
  printf '%s' "$pid"
}

# Returns 0 when the secrets file defines a non-empty OPENAI_API_KEY. Never prints the value.
secrets_has_api_key() {
  [ -n "${OPENAI_API_KEY:-}" ] && return 0
  [ -r "$BANK_AGENT_SECRETS_FILE" ] && grep -Eq '^[[:space:]]*OPENAI_API_KEY[[:space:]]*=[[:space:]]*[^[:space:]]+' "$BANK_AGENT_SECRETS_FILE"
}

# Prints a non-secret property value (e.g. OPENAI_MODEL) from the secrets file.
secrets_value() {
  [ -r "$BANK_AGENT_SECRETS_FILE" ] || return 1
  awk -F'=' -v key="$1" '$1 ~ "^[[:space:]]*"key"[[:space:]]*$" {sub(/^[^=]*=[[:space:]]*/, ""); print; exit}' "$BANK_AGENT_SECRETS_FILE"
}

# Galileo instrumentation: effective value of a NON-secret switch (environment variable first, then galileo.properties).
# Never use it for credentials.
galileo_switch() {
  local key="$1" default="$2" value=""
  value="$(printenv "$key" 2>/dev/null || true)"
  if [ -z "$value" ] && [ -r "$BANK_AGENT_GALILEO_FILE" ]; then
    value="$(awk -F'=' -v key="$key" '$1 ~ "^[[:space:]]*"key"[[:space:]]*$" {sub(/^[^=]*=[[:space:]]*/, ""); print; exit}' "$BANK_AGENT_GALILEO_FILE")"
  fi
  printf '%s' "${value:-$default}"
}

# JVM options for the project trust store (call prepare_truststore first).
tls_jvm_opts() {
  TLS_JVM_OPTS=()
  if [ -n "${TRUSTSTORE_PATH:-}" ]; then
    TLS_JVM_OPTS=("-Djavax.net.ssl.trustStore=$TRUSTSTORE_PATH" -Djavax.net.ssl.trustStoreType=PKCS12
                  "-Djavax.net.ssl.trustStorePassword=$BANK_AGENT_TRUSTSTORE_PASSWORD")
  fi
}

# Runs the Galileo CLI (com.demo.bankagent.cli.GalileoCli) from the application jar. It uses the same derivation
# code as the application and never prints credentials. Requires require_java21, ensure_jar, prepare_truststore.
run_galileo_cli() {
  tls_jvm_opts
  "$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 ${TLS_JVM_OPTS[@]+"${TLS_JVM_OPTS[@]}"} \
    -Dloader.main=com.demo.bankagent.cli.GalileoCli -cp "$BANK_AGENT_JAR" \
    org.springframework.boot.loader.launch.PropertiesLauncher "$@"
}

# --- TLS trust -----------------------------------------------------------------------------
# Corporate networks (e.g. Cisco Secure Access) may re-sign TLS traffic with a root CA that is
# trusted by the macOS keychain but not by the JDK's bundled cacerts. To reach OpenAI from Java we
# build a project-local trust store = JDK cacerts + CA certificates from the macOS System/login
# keychains. It is passed to this application's JVM only; the JDK installation is never modified.

_truststore_sources() {
  printf '%s\n' "$JAVA_HOME/lib/security/cacerts" /Library/Keychains/System.keychain \
    "$HOME/Library/Keychains/login.keychain-db"
}

truststore_is_stale() {
  local ts="$1" src
  [ -s "$ts" ] || return 0
  while IFS= read -r src; do
    if [ -e "$src" ] && [ "$src" -nt "$ts" ]; then return 0; fi
  done <<EOF_SOURCES
$(_truststore_sources)
EOF_SOURCES
  return 1
}

build_truststore() {
  local ts="$1" work count=0 f alias
  command -v security >/dev/null 2>&1 || { warn "macOS 'security' tool not found; skipping keychain trust store."; return 1; }
  mkdir -p "$(dirname "$ts")"
  work="$(mktemp -d "${TMPDIR:-/tmp}/bankagent-tls.XXXXXX")"
  "$JAVA_HOME/bin/keytool" -importkeystore -noprompt -srckeystore "$JAVA_HOME/lib/security/cacerts" \
    -srcstorepass changeit -destkeystore "$work/truststore.p12" -deststoretype PKCS12 \
    -deststorepass "$BANK_AGENT_TRUSTSTORE_PASSWORD" >/dev/null 2>&1 || { rm -rf "$work"; warn "Could not copy JDK cacerts."; return 1; }
  for kc in /Library/Keychains/System.keychain "$HOME/Library/Keychains/login.keychain-db"; do
    [ -r "$kc" ] || continue
    security find-certificate -a -p "$kc" 2>/dev/null >> "$work/keychain.pem" || true
  done
  if [ -s "$work/keychain.pem" ]; then
    awk -v dir="$work" '/-----BEGIN CERTIFICATE-----/ {n++; file=sprintf("%s/cert-%03d.pem", dir, n)} file {print > file} /-----END CERTIFICATE-----/ {close(file); file=""}' "$work/keychain.pem"
    for f in "$work"/cert-*.pem; do
      [ -e "$f" ] || continue
      # Only CA certificates become trust anchors.
      openssl x509 -in "$f" -noout -text 2>/dev/null | grep -q "CA:TRUE" || continue
      alias="macos-keychain-$(basename "$f" .pem)"
      if "$JAVA_HOME/bin/keytool" -importcert -noprompt -alias "$alias" -file "$f" -keystore "$work/truststore.p12" \
           -storetype PKCS12 -storepass "$BANK_AGENT_TRUSTSTORE_PASSWORD" >/dev/null 2>&1; then
        count=$((count + 1))
      fi
    done
  fi
  mv -f "$work/truststore.p12" "$ts"
  chmod 0644 "$ts"
  rm -rf "$work"
  info "Built project trust store ($count CA certificates from macOS keychains + JDK cacerts): $ts"
  return 0
}

# Sets TRUSTSTORE_PATH (empty = JDK default) for the application JVM.
# BANK_AGENT_TRUSTSTORE=<path> uses a custom store, BANK_AGENT_TRUSTSTORE=none disables this feature.
prepare_truststore() {
  TRUSTSTORE_PATH=""
  local ts="${BANK_AGENT_TRUSTSTORE:-$BANK_AGENT_TRUSTSTORE_DEFAULT}"
  if [ "${BANK_AGENT_TRUSTSTORE:-}" = "none" ]; then
    info "Project trust store disabled (BANK_AGENT_TRUSTSTORE=none); using JDK default cacerts."
    return 0
  fi
  if [ "$(uname -s)" != "Darwin" ] && [ -z "${BANK_AGENT_TRUSTSTORE:-}" ]; then
    return 0
  fi
  if [ -z "${BANK_AGENT_TRUSTSTORE:-}" ] && truststore_is_stale "$ts"; then
    build_truststore "$ts" || { warn "Continuing with JDK default cacerts."; return 0; }
  fi
  if [ -s "$ts" ]; then
    TRUSTSTORE_PATH="$ts"
  fi
}

# Builds the jar when it is missing or older than any source file.
ensure_jar() {
  if [ "${1:-}" = "force" ] || [ ! -f "$BANK_AGENT_JAR" ] || \
     [ -n "$(find "$BANK_AGENT_ROOT/src/main" "$BANK_AGENT_ROOT/pom.xml" -newer "$BANK_AGENT_JAR" -type f 2>/dev/null | head -1)" ]; then
    info "Building application jar (tests skipped; run scripts/test.sh for tests)..."
    (cd "$BANK_AGENT_ROOT" && ./mvnw -B -q -DskipTests package) || die "Maven build failed. Run './mvnw -B package' for details."
  fi
}
