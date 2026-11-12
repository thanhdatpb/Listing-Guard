#!/usr/bin/env bash
# Runs the tsdr-status job on this host (ADR-009, "TSDR status check"; docs/deploy-oracle.md section 8a). It asks the USPTO TSDR
# whether the marks that recent findings still name are dead, and writes a report under catalogs/raw/tsdr/out/<UTC timestamp>/
# (report.json, dead-marks.csv). It only proposes: it changes no catalog and no finding. One run at a time (flock on the host;
# the job also holds a PostgreSQL advisory lock), one log file per run under logs/tsdr-status/. Every argument goes to the job:
#   ./deploy/oracle/tsdr-status.sh --dry-run                       # which marks would be asked about; nothing is asked or written
#   ./deploy/oracle/tsdr-status.sh --input-dir=tsdr-saved          # read TSDR files you saved yourself (no network, no key)
#   ./deploy/oracle/tsdr-status.sh [--max-marks=50]                # ask the TSDR (needs TSDR_DOWNLOAD_ENABLED=true and the key)
# Exit codes: the job's own (0 done, non-zero when it stopped early: a refused key, a limit that lasts, another run holding the
# lock), 75 when another run of this script holds the host lock, 1 when the environment file or a setting is wrong. The key is
# never printed: the job does not log it and this script does not echo the environment.
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd -- "$SCRIPT_DIR/../.." && pwd)
ENV_FILE=${ENV_FILE:-$ROOT/.env}
LOG_DIR=$ROOT/logs/tsdr-status
LOCK_FILE=${TSDR_STATUS_LOCK_FILE:-$ROOT/.tsdr-status.lock}

read_env() {
  local key=$1 value
  value=$(awk -v wanted="$key" '
    /^[[:space:]]*#/ { next }
    {
      line = $0
      sub(/^[[:space:]]+/, "", line)
      equal = index(line, "=")
      if (equal && substr(line, 1, equal - 1) == wanted) {
        print substr(line, equal + 1)
        exit
      }
    }' "$ENV_FILE")
  value=${value%$'\r'}
  if [[ $value == \"*\" ]]; then
    value=${value:1:${#value}-2}
  elif [[ $value == \'*\' ]]; then
    value=${value:1:${#value}-2}
  fi
  printf '%s' "$value"
}

if [[ ! -r $ENV_FILE ]]; then
  printf 'Cannot read environment file: %s\n' "$ENV_FILE" >&2
  exit 1
fi

log_retention=$(read_env TSDR_STATUS_LOG_RETENTION)
log_retention=${log_retention:-30}
if [[ ! $log_retention =~ ^[1-9][0-9]*$ ]]; then
  printf 'TSDR_STATUS_LOG_RETENTION must be a positive integer.\n' >&2
  exit 1
fi

# one run at a time: a second one started by cron while the first still waits for the TSDR leaves at once
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  printf 'Another tsdr-status run holds %s; this one did nothing.\n' "$LOCK_FILE" >&2
  exit 75
fi

compose_files=$(read_env COMPOSE_FILE)
compose_files=${compose_files:-docker-compose.prod.yml}
IFS=: read -r -a compose_file_list <<< "$compose_files"
compose_args=()
for compose_file in "${compose_file_list[@]}"; do
  if [[ $compose_file != /* ]]; then
    compose_file=$ROOT/$compose_file
  fi
  if [[ ! -f $compose_file ]]; then
    printf 'Compose file does not exist: %s\n' "$compose_file" >&2
    exit 1
  fi
  compose_args+=(-f "$compose_file")
done

mkdir -p "$LOG_DIR" "$ROOT/catalogs/raw"
log_file=$LOG_DIR/tsdr-status-$(date -u +%Y%m%dT%H%M%SZ).log
printf 'tsdr-status started %s; log: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$log_file"
set +e
docker compose --env-file "$ENV_FILE" "${compose_args[@]}" --profile prod --profile tsdr-status \
  run --rm -T tsdr-status "$@" > "$log_file" 2>&1
status=$?
set -e
# the summary lines go to cron's mail as well; the report folder is named in the last of them
grep -E 'tsdr-status( run [0-9]+)?:|tsdr-status report:|needs attention|WARN|ERROR' "$log_file" | tail -n 20 || true
printf 'tsdr-status finished with exit code %s.\n' "$status"

shopt -s nullglob
logs=("$LOG_DIR"/tsdr-status-*.log)
if (( ${#logs[@]} > log_retention )); then
  mapfile -t newest < <(printf '%s\n' "${logs[@]}" | sort -r)
  for old_log in "${newest[@]:log_retention}"; do
    rm -- "$old_log"
  done
fi
exit "$status"
