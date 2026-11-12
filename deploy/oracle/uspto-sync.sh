#!/usr/bin/env bash
# Runs the uspto-sync job on this host (ADR-009, PR A2d; docs/deploy-oracle.md section 8). One run at a time (flock on the
# host; the job also holds a PostgreSQL advisory lock), never with too little free disk, one log file per run under
# logs/uspto-sync/. Every argument goes to the job, for example:
#   ./deploy/oracle/uspto-sync.sh --backfile --input-dir=uspto-inbox/backfile --no-activate
#   ./deploy/oracle/uspto-sync.sh --input-dir=uspto-inbox/daily
#   ./deploy/oracle/uspto-sync.sh --dry-run
# Without --input-dir the job downloads from the USPTO Open Data Portal only when USPTO_SYNC_DOWNLOAD_ENABLED=true in .env
# (every endpoint unverified); otherwise it converts the stored files when they changed. Exit codes: the job's own (0 done,
# non-zero when it needs you), 75 when another run holds the lock, 1 when the disk is too full to start.
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd -- "$SCRIPT_DIR/../.." && pwd)
ENV_FILE=${ENV_FILE:-$ROOT/.env}
LOG_DIR=$ROOT/logs/uspto-sync
LOCK_FILE=${USPTO_SYNC_LOCK_FILE:-$ROOT/.uspto-sync.lock}

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

positive_int() {
  local name=$1 value=$2
  if [[ ! $value =~ ^[1-9][0-9]*$ ]]; then
    printf '%s must be a positive integer.\n' "$name" >&2
    exit 1
  fi
}

if [[ ! -r $ENV_FILE ]]; then
  printf 'Cannot read environment file: %s\n' "$ENV_FILE" >&2
  exit 1
fi

warn_gb=$(read_env USPTO_SYNC_WARN_FREE_GB)
warn_gb=${warn_gb:-30}
min_gb=$(read_env USPTO_SYNC_MIN_FREE_GB)
min_gb=${min_gb:-15}
log_retention=$(read_env USPTO_SYNC_LOG_RETENTION)
log_retention=${log_retention:-30}
positive_int USPTO_SYNC_WARN_FREE_GB "$warn_gb"
positive_int USPTO_SYNC_MIN_FREE_GB "$min_gb"
positive_int USPTO_SYNC_LOG_RETENTION "$log_retention"

# one run at a time: a second one started by cron while the first still converts leaves at once
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  printf 'Another uspto-sync run holds %s; this one did nothing.\n' "$LOCK_FILE" >&2
  exit 75
fi

# free disk where the files go and where PostgreSQL keeps the catalog versions (the Docker data root)
free_gb() {
  df -P -B1G -- "$1" | awk 'NR == 2 { print $4 }'
}
check_disk() {
  local what=$1 path=$2 free
  [[ -e $path ]] || return 0
  free=$(free_gb "$path")
  if (( free < min_gb )); then
    printf 'Only %s GB free for %s (%s), below USPTO_SYNC_MIN_FREE_GB=%s: the sync does not start. Free space first ' \
      "$free" "$what" "$path" "$min_gb" >&2
    printf '(old backfile and daily files under catalogs/raw/uspto-sync/files, old backups, docker image prune).\n' >&2
    exit 1
  fi
  if (( free < warn_gb )); then
    printf 'WARNING: only %s GB free for %s (%s), below USPTO_SYNC_WARN_FREE_GB=%s.\n' "$free" "$what" "$path" "$warn_gb" >&2
  fi
}
mkdir -p "$ROOT/catalogs/raw"
check_disk "the catalog files" "$ROOT/catalogs/raw"
docker_root=$(docker info --format '{{.DockerRootDir}}' 2>/dev/null || true)
if [[ -n $docker_root ]]; then
  check_disk "the database (Docker data root)" "$docker_root"
fi
if [[ -d $ROOT/catalogs/raw/uspto-sync ]]; then
  printf 'The sync folder holds %s.\n' "$(du -sh -- "$ROOT/catalogs/raw/uspto-sync" | cut -f1)"
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

mkdir -p "$LOG_DIR"
log_file=$LOG_DIR/uspto-sync-$(date -u +%Y%m%dT%H%M%SZ).log
printf 'uspto-sync started %s; log: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$log_file"
set +e
docker compose --env-file "$ENV_FILE" "${compose_args[@]}" --profile prod --profile uspto-sync \
  run --rm -T uspto-sync "$@" > "$log_file" 2>&1
status=$?
set -e
# the job never prints the API key; the summary lines go to cron's mail as well
grep -E 'uspto-sync run [0-9]+:|needs attention|WARN|ERROR' "$log_file" | tail -n 20 || true
printf 'uspto-sync finished with exit code %s.\n' "$status"

shopt -s nullglob
logs=("$LOG_DIR"/uspto-sync-*.log)
if (( ${#logs[@]} > log_retention )); then
  mapfile -t newest < <(printf '%s\n' "${logs[@]}" | sort -r)
  for old_log in "${newest[@]:log_retention}"; do
    rm -- "$old_log"
  done
fi
exit "$status"
