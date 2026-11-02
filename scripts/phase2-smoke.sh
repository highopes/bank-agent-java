#!/usr/bin/env bash
# Phase 2 real-service smoke test - EXPLICIT OPT-IN (uses the real OpenAI API and the real AO backend).
# It talks to an already running application (scripts/run.sh --background [--observe] [--control]), asks three
# read-only banking questions (O-A, O-B, O-C), prints trace IDs and Control decisions and then reads the traces back
# from the AO API (read-only) to show the span tree, token counts and control spans. No transfer is made.
#
# Usage: scripts/phase2-smoke.sh --yes                 run the three questions + read-back
#        scripts/phase2-smoke.sh --trace <trace-id>... only read back existing traces (OTel hex or AO UUID)
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

MODE=""
TRACES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --yes|-y) MODE=smoke ;;
    --trace) MODE=trace; shift; while [ $# -gt 0 ]; do TRACES+=("$1"); shift; done; break ;;
    -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "Unknown option: $1 (see --help)" ;;
  esac
  shift || true
done
[ -n "$MODE" ] || die "This smoke test calls OpenAI and the AO backend (costs tokens). Re-run with --yes to confirm."

require_java21
ensure_jar
prepare_truststore
if [ "$MODE" = "trace" ]; then
  [ "${#TRACES[@]}" -gt 0 ] || die "Usage: scripts/phase2-smoke.sh --trace <trace-id>..."
  run_phase2_cli trace "${TRACES[@]}"
  exit $?
fi
if ! curl -fsS -m 3 "http://$BANK_AGENT_HOST:$BANK_AGENT_PORT/actuator/health" >/dev/null 2>&1; then
  die "The application is not running at http://$BANK_AGENT_HOST:$BANK_AGENT_PORT (start it with scripts/run.sh --background --observe)."
fi
info "Running Phase 2 smoke questions against http://$BANK_AGENT_HOST:$BANK_AGENT_PORT ..."
if run_phase2_cli smoke; then
  ok "Phase 2 smoke test passed."
else
  status=$?
  fail "Phase 2 smoke test failed (exit $status)."
  exit "$status"
fi
