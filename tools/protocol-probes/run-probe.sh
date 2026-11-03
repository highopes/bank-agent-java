#!/usr/bin/env bash
# Runs a protocol verification probe (local use only) with the project's Java 21, Maven classpath and project
# trust store. AO_REALM / AO_O11Y_TOKEN are read from the private galileo.properties into THIS process
# only and are never printed.
# Usage: tools/protocol-probes/run-probe.sh <OtlpProbe|ControlProbe|ErrorSpanProbe> [-Dprobe.key=value ...] [args...]
set -euo pipefail
source "$(dirname "$0")/../../scripts/lib/common.sh"

PROBE="${1:?Usage: tools/protocol-probes/run-probe.sh <ProbeName> [-Dkey=value ...] [args...]}"
shift
require_java21
prepare_truststore

GALILEO_FILE="${BANK_AGENT_GALILEO_FILE:-$HOME/.config/bank-agent-java/galileo.properties}"
_prop() { [ -r "$GALILEO_FILE" ] && awk -v key="$1" 'index($0, key"=")==1 {sub(/^[^=]*=/, ""); print; exit}' "$GALILEO_FILE"; }
export AO_REALM="${AO_REALM:-$(_prop AO_REALM)}"
export AO_O11Y_TOKEN="${AO_O11Y_TOKEN:-$(_prop AO_O11Y_TOKEN)}"

CP_FILE="$BANK_AGENT_ROOT/target/protocol-probes-classpath.txt"
if [ ! -s "$CP_FILE" ] || [ "$BANK_AGENT_ROOT/pom.xml" -nt "$CP_FILE" ]; then
  (cd "$BANK_AGENT_ROOT" && ./mvnw -B -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE" -Dmdep.includeScope=runtime) >/dev/null
fi

JVM_OPTS=(-Dfile.encoding=UTF-8)
if [ -n "$TRUSTSTORE_PATH" ]; then
  JVM_OPTS+=("-Djavax.net.ssl.trustStore=$TRUSTSTORE_PATH" -Djavax.net.ssl.trustStoreType=PKCS12
             "-Djavax.net.ssl.trustStorePassword=$BANK_AGENT_TRUSTSTORE_PASSWORD")
fi
SYS_PROPS=()
ARGS=()
for a in "$@"; do
  case "$a" in -D*) SYS_PROPS+=("$a") ;; *) ARGS+=("$a") ;; esac
done
exec "$JAVA_HOME/bin/java" "${JVM_OPTS[@]}" ${SYS_PROPS[@]+"${SYS_PROPS[@]}"} -cp "$(cat "$CP_FILE")" \
  "$BANK_AGENT_ROOT/tools/protocol-probes/$PROBE.java" ${ARGS[@]+"${ARGS[@]}"}
