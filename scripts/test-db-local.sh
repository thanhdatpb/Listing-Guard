#!/usr/bin/env bash
# A local PostgreSQL 16 with pgvector for the integration tests, for a machine without Docker (cloud sessions, CI-less boxes).
# It prints the three variables that make TestcontainersConfig use this database instead of a container (docs/testing.md):
#
#   eval "$(scripts/test-db-local.sh)"
#   ./mvnw -B -pl api -am verify
#
# What it does:
#   1. installs postgresql-16 and postgresql-16-pgvector with apt when they are missing (Ubuntu/Debian; needs root or sudo);
#   2. creates a throwaway cluster once and starts it on 127.0.0.1 (trust authentication, listens on loopback only, fsync off:
#      for tests, never for data you want to keep);
#   3. creates a NEW database for this run, named lg_test_<date>_<time>, so a test that assumes an empty database starts clean;
#   4. prints the export lines on stdout (everything else goes to stderr, so the eval above is safe).
#
# Options:
#   --clean   also drop the older lg_test_* databases this script made (the new one stays)
#   --stop    stop the cluster and exit (data stays; a later run starts it again)
#   --port N  cluster port (default 54329, or LG_TEST_PGPORT)
#   -h        this text
#
# Environment: LG_TEST_PGPORT, LG_TEST_PGDATA (data directory; default is under the postgres user's home when run as root,
# else ~/.cache/listing-guard/pgdata). Not under /tmp: some sandboxes reset its mode and postgres then stops.
set -euo pipefail

PORT="${LG_TEST_PGPORT:-54329}"
CLEAN=0
STOP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --clean) CLEAN=1 ;;
    --stop) STOP=1 ;;
    --port) PORT="${2:?--port needs a number}"; shift ;;
    -h|--help) sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

log() { echo "test-db-local: $*" >&2; }

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
  SUDO="sudo"
fi

BIN=/usr/lib/postgresql/16/bin
need_install=0
[ -x "$BIN/initdb" ] || need_install=1
[ -f /usr/share/postgresql/16/extension/vector.control ] || need_install=1
if [ "$need_install" -eq 1 ]; then
  command -v apt-get >/dev/null || { log "PostgreSQL 16 with pgvector is missing and apt-get is not available: install postgresql-16 and the pgvector extension yourself"; exit 1; }
  log "installing postgresql-16 and postgresql-16-pgvector with apt"
  $SUDO apt-get update -qq >&2
  $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq postgresql-16 postgresql-16-pgvector >&2
  # The package starts its own cluster on 5432; this script uses its own, so stop that one if it was just created.
  $SUDO systemctl stop postgresql >/dev/null 2>&1 || $SUDO service postgresql stop >/dev/null 2>&1 || true
fi

# postgres refuses to run as root: as root the cluster belongs to the postgres user, otherwise to the caller.
if [ "$(id -u)" -eq 0 ]; then
  PG_USER=postgres
  DATA="${LG_TEST_PGDATA:-/var/lib/postgresql/listing-guard-test}"
  as_pg() { runuser -u postgres -- "$@"; }
else
  PG_USER="$(id -un)"
  DATA="${LG_TEST_PGDATA:-$HOME/.cache/listing-guard/pgdata}"
  as_pg() { "$@"; }
fi

if [ "$STOP" -eq 1 ]; then
  if [ -d "$DATA" ]; then as_pg "$BIN/pg_ctl" -D "$DATA" -m fast stop >&2 || true; fi
  exit 0
fi

if [ ! -f "$DATA/PG_VERSION" ]; then
  log "creating the cluster in $DATA"
  if [ "$(id -u)" -eq 0 ]; then
    mkdir -p "$(dirname "$DATA")"
    install -d -o postgres -g postgres -m 700 "$DATA"
  else
    mkdir -p "$DATA"
    chmod 700 "$DATA"
  fi
  as_pg "$BIN/initdb" -D "$DATA" -U "$PG_USER" --auth=trust --encoding=UTF8 --locale=C.UTF-8 >&2
fi

if ! "$BIN/pg_isready" -q -h 127.0.0.1 -p "$PORT"; then
  log "starting the cluster on 127.0.0.1:$PORT"
  # The integration tests keep many Spring contexts (one Hikari pool each) open, hence the generous max_connections.
  as_pg "$BIN/pg_ctl" -D "$DATA" -l "$DATA/server.log" -w start -o \
    "-p $PORT -c listen_addresses=127.0.0.1 -c unix_socket_directories=$DATA -c max_connections=1500 -c shared_buffers=256MB -c fsync=off -c synchronous_commit=off -c full_page_writes=off" >&2
fi

psql_admin() { "$BIN/psql" -h 127.0.0.1 -p "$PORT" -U "$PG_USER" -d postgres -v ON_ERROR_STOP=1 -qAt "$@"; }

# V1 creates the extension vector, which needs a superuser: the role of the tests is one (a throwaway cluster).
if [ "$(psql_admin -c "SELECT count(*) FROM pg_roles WHERE rolname = 'listingguard'")" = "0" ]; then
  psql_admin -c "CREATE ROLE listingguard LOGIN SUPERUSER PASSWORD 'listingguard'" >&2
fi

DB="lg_test_$(date +%Y%m%d_%H%M%S)"
n=0
while [ "$(psql_admin -c "SELECT count(*) FROM pg_database WHERE datname = '$DB'")" != "0" ]; do
  n=$((n + 1)); DB="lg_test_$(date +%Y%m%d_%H%M%S)_$n"
done
psql_admin -c "CREATE DATABASE $DB OWNER listingguard" >&2
log "created database $DB"

if [ "$CLEAN" -eq 1 ]; then
  for old in $(psql_admin -c "SELECT datname FROM pg_database WHERE datname LIKE 'lg\_test\_%' AND datname <> '$DB'"); do
    psql_admin -c "DROP DATABASE IF EXISTS $old WITH (FORCE)" >&2
    log "dropped $old"
  done
fi

echo "export LG_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:$PORT/$DB"
echo "export LG_TEST_JDBC_USER=listingguard"
echo "export LG_TEST_JDBC_PASSWORD=listingguard"
