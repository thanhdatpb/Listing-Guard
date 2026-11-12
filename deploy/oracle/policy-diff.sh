#!/usr/bin/env bash
# Compares maintainer-saved policy pages with the previous hash snapshot and prunes reports beyond
# POLICY_MONITOR_KEEP_REPORTS (default 12). No network access is performed.
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd -- "$SCRIPT_DIR/../.." && pwd)
LOCK_FILE=${POLICY_DIFF_LOCK_FILE:-$ROOT/.policy-diff.lock}
mkdir -p "$ROOT/logs/policy-diff" "$ROOT/policy-monitor"
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  printf 'Another policy-diff run holds %s; this run did nothing.\n' "$LOCK_FILE" >&2
  exit 75
fi
stamp=$(date -u +%Y%m%dT%H%M%SZ)
log="$ROOT/logs/policy-diff/$stamp.log"
cd "$ROOT"
docker compose -f docker-compose.prod.yml --profile prod --profile policy-diff run --rm policy-diff "$@" 2>&1 | tee "$log"
