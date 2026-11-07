#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd -- "$SCRIPT_DIR/../.." && pwd)
ENV_FILE=${ENV_FILE:-$ROOT/.env}

usage() {
  printf 'Usage: %s <custom-format-dump> [--yes]\n' "$0" >&2
  exit 2
}

[[ $# -ge 1 && $# -le 2 ]] || usage
DUMP_FILE=$1
CONFIRM=${2:-}
[[ -z $CONFIRM || $CONFIRM == --yes ]] || usage
[[ -f $DUMP_FILE && -r $DUMP_FILE ]] || {
  printf 'Backup file is missing or unreadable: %s\n' "$DUMP_FILE" >&2
  exit 1
}
[[ -r $ENV_FILE ]] || {
  printf 'Cannot read environment file: %s\n' "$ENV_FILE" >&2
  exit 1
}

compose_files=$(awk '
  /^[[:space:]]*#/ { next }
  {
    line = $0
    sub(/^[[:space:]]+/, "", line)
    if (index(line, "COMPOSE_FILE=") == 1) {
      print substr(line, 14)
      exit
    }
  }' "$ENV_FILE")
compose_files=${compose_files%$'\r'}
if [[ $compose_files == \"*\" || $compose_files == \'*\' ]]; then
  compose_files=${compose_files:1:${#compose_files}-2}
fi
compose_files=${compose_files:-docker-compose.prod.yml}
IFS=: read -r -a compose_file_list <<< "$compose_files"
compose_args=()
for compose_file in "${compose_file_list[@]}"; do
  if [[ $compose_file != /* ]]; then
    compose_file=$ROOT/$compose_file
  fi
  [[ -f $compose_file ]] || {
    printf 'Compose file does not exist: %s\n' "$compose_file" >&2
    exit 1
  }
  compose_args+=(-f "$compose_file")
done

if [[ $CONFIRM != --yes ]]; then
  printf 'This replaces the configured Listing Guard database with %s. Continue? [y/N] ' "$DUMP_FILE" >&2
  read -r answer
  [[ $answer == y || $answer == Y || $answer == yes || $answer == YES ]] || {
    printf 'Restore cancelled.\n' >&2
    exit 1
  }
fi

docker compose --env-file "$ENV_FILE" "${compose_args[@]}" --profile prod exec -T postgres \
  sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" pg_restore --list' < "$DUMP_FILE" >/dev/null
docker compose --env-file "$ENV_FILE" "${compose_args[@]}" --profile prod stop api
docker compose --env-file "$ENV_FILE" "${compose_args[@]}" --profile prod exec -T postgres \
  sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --exit-on-error' \
  < "$DUMP_FILE"
docker compose --env-file "$ENV_FILE" "${compose_args[@]}" --profile prod up -d api
printf 'Restore completed and the API was started.\n'
