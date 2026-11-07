#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd -- "$SCRIPT_DIR/../.." && pwd)
ENV_FILE=${ENV_FILE:-$ROOT/.env}
BACKUP_DIR=$ROOT/backups

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

retention=$(read_env BACKUP_RETENTION_COUNT)
retention=${retention:-14}
if [[ ! $retention =~ ^[1-9][0-9]*$ ]]; then
  printf 'BACKUP_RETENTION_COUNT must be a positive integer.\n' >&2
  exit 1
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

mkdir -p "$BACKUP_DIR"
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
backup_file=$BACKUP_DIR/listing-guard-$timestamp.dump
if [[ -e $backup_file ]]; then
  printf 'A backup already exists for this UTC second: %s\n' "$backup_file" >&2
  exit 1
fi
temporary_file=$(mktemp "$BACKUP_DIR/.listing-guard-$timestamp.XXXXXX")
trap 'rm -f "$temporary_file"' EXIT
chmod 0600 "$temporary_file"

docker compose --env-file "$ENV_FILE" "${compose_args[@]}" --profile prod exec -T postgres \
  sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$temporary_file"
mv -- "$temporary_file" "$backup_file"
trap - EXIT
printf 'Created database backup: %s\n' "$backup_file"

bucket=$(read_env BACKUP_S3_BUCKET)
if [[ -n $bucket ]]; then
  if ! command -v aws >/dev/null 2>&1; then
    printf 'BACKUP_S3_BUCKET is set, but the AWS CLI is not installed.\n' >&2
    exit 1
  fi
  access_key=$(read_env BACKUP_S3_ACCESS_KEY_ID)
  secret_key=$(read_env BACKUP_S3_SECRET_ACCESS_KEY)
  region=$(read_env BACKUP_S3_REGION)
  endpoint=$(read_env BACKUP_S3_ENDPOINT)
  prefix=$(read_env BACKUP_S3_PREFIX)
  session_token=$(read_env BACKUP_S3_SESSION_TOKEN)
  if [[ -z $access_key || -z $secret_key || -z $region ]]; then
    printf 'S3 upload needs BACKUP_S3_ACCESS_KEY_ID, BACKUP_S3_SECRET_ACCESS_KEY, and BACKUP_S3_REGION.\n' >&2
    exit 1
  fi
  destination="s3://$bucket"
  if [[ -n $prefix ]]; then
    destination+="/${prefix#/}"
  fi
  destination+="/$(basename -- "$backup_file")"
  aws_args=(s3 cp "$backup_file" "$destination" --only-show-errors --region "$region")
  if [[ -n $endpoint ]]; then
    aws_args+=(--endpoint-url "$endpoint")
  fi
  AWS_ACCESS_KEY_ID=$access_key AWS_SECRET_ACCESS_KEY=$secret_key AWS_SESSION_TOKEN=$session_token \
    AWS_DEFAULT_REGION=$region aws "${aws_args[@]}"
  printf 'Uploaded backup to configured object storage.\n'
fi

shopt -s nullglob
backups=("$BACKUP_DIR"/listing-guard-*.dump)
if (( ${#backups[@]} > retention )); then
  mapfile -t newest < <(printf '%s\n' "${backups[@]}" | sort -r)
  for old_backup in "${newest[@]:retention}"; do
    rm -- "$old_backup"
  done
fi
