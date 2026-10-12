#!/usr/bin/env bash
# Logical backup of the pgms PostgreSQL database (pg_dump custom format) taken through the running postgres container.
#
#   ./scripts/backup-db.sh                         # -> backups/pgms_<UTC timestamp>.dump (+ .sha256), prunes > 14 days
#   BACKUP_RETENTION_DAYS=30 ./scripts/backup-db.sh
#   ./scripts/backup-db.sh --no-prune
#
# Env (all optional):
#   PG_CONTAINER           container to exec into         (default: pgms-postgres-1, must belong to compose project pgms)
#   PG_DATABASE            database to dump               (default: pgms)
#   BACKUP_DIR             output directory               (default: <repo>/backups, git-ignored)
#   BACKUP_RETENTION_DAYS  delete pgms_*.dump older than N days in BACKUP_DIR (default: 14, 0 = keep everything)
#
# Credentials: the dump runs as the admin user (POSTGRES_ADMIN_USER in .env). The password is never read or passed
# on the command line: pg_dump runs inside the container, which already has POSTGRES_USER / POSTGRES_PASSWORD.
# Roles (pgms_app, analytics_ro) are cluster-global and NOT part of the dump; they are recreated by
# infra/postgres/init/01-roles.sh on a fresh volume.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
PG_CONTAINER=${PG_CONTAINER:-pgms-postgres-1}
PG_DATABASE=${PG_DATABASE:-pgms}
BACKUP_DIR=${BACKUP_DIR:-$ROOT/backups}
BACKUP_RETENTION_DAYS=${BACKUP_RETENTION_DAYS:-14}
PRUNE=true

for arg in "$@"; do
  case $arg in
    --no-prune) PRUNE=false ;;
    -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

log() { printf '[backup %s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
die() { printf '[backup] ERROR: %s\n' "$*" >&2; exit 1; }

[[ "$BACKUP_RETENTION_DAYS" =~ ^[0-9]+$ ]] || die "BACKUP_RETENTION_DAYS must be a non-negative integer"
[[ "$PG_DATABASE" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || die "invalid database name: $PG_DATABASE"

# --- the container must be ours and running
project=$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "$PG_CONTAINER" 2>/dev/null) \
  || die "container $PG_CONTAINER not found (is the stack up? make up)"
[[ "$project" == "pgms" ]] || die "$PG_CONTAINER belongs to compose project '$project', not 'pgms' - refusing"
[[ "$(docker inspect -f '{{.State.Running}}' "$PG_CONTAINER")" == "true" ]] || die "$PG_CONTAINER is not running"

pg() { docker exec -i "$PG_CONTAINER" sh -c "PGPASSWORD=\"\$POSTGRES_PASSWORD\" $1"; }

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR" 2>/dev/null || true
umask 077

stamp=$(date -u +%Y%m%dT%H%M%SZ)
file="$BACKUP_DIR/${PG_DATABASE}_${stamp}.dump"
partial="$file.partial"
trap 'rm -f "$partial"' EXIT

version=$(pg 'psql -U "$POSTGRES_USER" -d '"$PG_DATABASE"' -Atc "select version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1"' 2>/dev/null || echo "?")
log "dumping database '$PG_DATABASE' from $PG_CONTAINER (flyway version $version)"
start=$(date +%s)
pg 'pg_dump -U "$POSTGRES_USER" -d '"$PG_DATABASE"' --format=custom --compress=6 --no-password' > "$partial" \
  || die "pg_dump failed"

# --- verify the archive is readable before declaring success
entries=$(pg 'pg_restore --list' < "$partial" | grep -c ' TABLE DATA ' || true)
[[ "$entries" -gt 0 ]] || die "archive has no TABLE DATA entries - not keeping it"
mv "$partial" "$file"
trap - EXIT

if command -v shasum >/dev/null; then (cd "$BACKUP_DIR" && shasum -a 256 "$(basename "$file")" > "$(basename "$file").sha256")
elif command -v sha256sum >/dev/null; then (cd "$BACKUP_DIR" && sha256sum "$(basename "$file")" > "$(basename "$file").sha256"); fi

size=$(du -h "$file" | cut -f1)
log "OK  $file  ($size, $entries tables with data, $(( $(date +%s) - start ))s)"

# --- retention
if $PRUNE && [[ "$BACKUP_RETENTION_DAYS" -gt 0 ]]; then
  old=$(find "$BACKUP_DIR" -maxdepth 1 -type f -name "${PG_DATABASE}_*.dump" -mtime "+$BACKUP_RETENTION_DAYS" | sort)
  if [[ -n "$old" ]]; then
    while IFS= read -r f; do
      rm -f "$f" "$f.sha256"
      log "pruned $(basename "$f") (older than $BACKUP_RETENTION_DAYS days)"
    done <<< "$old"
  fi
fi

log "backups in $BACKUP_DIR: $(find "$BACKUP_DIR" -maxdepth 1 -type f -name "${PG_DATABASE}_*.dump" | wc -l | tr -d ' ')"
echo "$file"
