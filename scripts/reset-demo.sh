#!/usr/bin/env bash
# Restores the simulated demo data (balances, transactions, drafts, audit) to the initial seed.
# If the app is running, the local-only reset API is used (also clears conversations).
# If it is not running, the local H2 files are removed and re-seeded on the next start.
# Usage: scripts/reset-demo.sh [--yes]
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

ASSUME_YES=false
case "${1:-}" in
  --yes|-y) ASSUME_YES=true ;;
  "") ;;
  *) die "Usage: scripts/reset-demo.sh [--yes]" ;;
esac

BASE="http://$BANK_AGENT_HOST:$BANK_AGENT_PORT"
if curl -fsS -m 3 "$BASE/actuator/health" >/dev/null 2>&1; then
  info "Application is running at $BASE; calling the local reset API..."
  response="$(curl -sS -m 30 -X POST -w '\n%{http_code}' "$BASE/api/demo/reset")"
  code="$(printf '%s' "$response" | tail -n 1)"
  if [ "$code" = "200" ]; then
    ok "Demo data reset. All conversations were cleared; refresh the browser page."
    exit 0
  fi
  die "Reset API returned HTTP $code: $(printf '%s' "$response" | sed '$d')"
fi

DB_DIR="$BANK_AGENT_DATA_DIR/h2"
info "Application is not running. Local demo database: $DB_DIR"
if ! ls "$DB_DIR"/bankagent.* >/dev/null 2>&1; then
  ok "No local demo database exists yet; it will be created with seed data on the next start."
  exit 0
fi
if [ "$ASSUME_YES" != true ]; then
  printf 'Delete the local demo database files (they are re-seeded on next start)? [y/N] '
  read -r answer
  case "$answer" in y|Y|yes|YES) ;; *) info "Aborted."; exit 0 ;; esac
fi
rm -f "$DB_DIR"/bankagent.*
ok "Local demo database removed. Start the app with scripts/run.sh to re-create the seed data."
