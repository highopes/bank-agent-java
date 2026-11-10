#!/usr/bin/env bash
# Runs the automated test suite. Fully offline for the application: no OpenAI, Splunk AO or Agent Control calls (the
# private secrets and galileo files are blocked by pom.xml), in-memory H2; instrumentation tests record spans in memory,
# and OtelJavaAgentIT starts the packaged jar with the real OpenTelemetry Java agent against loopback mocks (the agent
# jar itself comes from Maven Central / ~/.m2 like every other build dependency).
# Exit code = Maven result. For real-service checks use scripts/galileo-smoke.sh (explicit opt-in).
# Usage: scripts/test.sh [extra maven args]
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

require_java21
ok "Using Java 21 for this process only: $JAVA_HOME"
cd "$BANK_AGENT_ROOT"
info "Running: ./mvnw -B clean verify $*"
if ./mvnw -B clean verify "$@"; then
  ok "All tests passed."
else
  status=$?
  fail "Tests failed (exit $status). Reports: target/surefire-reports/ and target/failsafe-reports/"
  exit "$status"
fi
