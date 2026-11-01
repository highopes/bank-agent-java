#!/usr/bin/env bash
# Runs the automated test suite (offline: no OpenAI calls, in-memory H2). Exit code = Maven result.
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
