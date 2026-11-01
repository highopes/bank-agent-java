#!/usr/bin/env bash
# Stops a Bank Agent instance started with scripts/run.sh --background.
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

if ! pid="$(app_pid)"; then
  rm -f "$BANK_AGENT_PID_FILE"
  info "Bank Agent is not running (no live pid file)."
  exit 0
fi
info "Stopping Bank Agent (pid $pid)..."
kill "$pid"
for _ in $(seq 1 30); do
  if ! kill -0 "$pid" 2>/dev/null; then
    rm -f "$BANK_AGENT_PID_FILE"
    ok "Stopped."
    exit 0
  fi
  sleep 1
done
die "Process $pid did not stop within 30s; stop it manually with: kill $pid"
