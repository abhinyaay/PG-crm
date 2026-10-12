#!/usr/bin/env bash
# Restore a pg_dump custom-format backup (from scripts/backup-db.sh) through the running postgres container.
#
# SAFE BY DEFAULT: restores into a NEW scratch database (pgms_restore_check) and verifies it by comparing per-table
# row counts with the live database. The live database is only overwritten with --force-overwrite plus a typed
# confirmation.
#
#   ./scripts/restore-db.sh                                  # latest backups/pgms_*.dump -> pgms_restore_check, verify
#   ./scripts/restore-db.sh --drop-after                     # ... and drop the scratch database afterwards
#   ./scripts/restore-db.sh backups/pgms_20261013T101500Z.dump --target pgms_yesterday
#   ./scripts/restore-db.sh <file> --target pgms --force-overwrite   # DISASTER RECOVERY ONLY (see docs/OPERATIONS.md)
#
# Options:
#   --target DB         database to restore into (default: pgms_restore_check)
#   --force-overwrite   required when --target is the live database (pgms); you will be asked to type its name
#                       (or set RESTORE_CONFIRM=<db> for non-interactive use). A safety backup is taken first.
#   --drop-after        drop the scratch target after verification (never allowed for the live database)
#   --yes               do not ask before dropping/recreating an existing *scratch* target
#
# Env: PG_CONTAINER (default pgms-postgres-1), LIVE_DATABASE (default pgms), BACKUP_DIR (default <repo>/backups)
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
PG_CONTAINER=${PG_CONTAINER:-pgms-postgres-1}
LIVE_DATABASE=${LIVE_DATABASE:-pgms}
BACKUP_DIR=${BACKUP_DIR:-$ROOT/backups}
TARGET=pgms_restore_check
FORCE_OVERWRITE=false
DROP_AFTER=false
ASSUME_YES=false
FILE=""

while [[ $# -gt 0 ]]; do
  case $1 in
    --target) TARGET=${2:?--target needs a database name}; shift 2 ;;
    --force-overwrite) FORCE_OVERWRITE=true; shift ;;
    --drop-after) DROP_AFTER=true; shift ;;
    --yes|-y) ASSUME_YES=true; shift ;;
    -h|--help) sed -n '2,23p' "$0"; exit 0 ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) [[ -z "$FILE" ]] || { echo "only one backup file may be given" >&2; exit 2; }; FILE=$1; shift ;;
  esac
done

log() { printf '[restore %s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
die() { printf '[restore] ERROR: %s\n' "$*" >&2; exit 1; }
confirm() { # confirm <prompt>  (yes/no)
  $ASSUME_YES && return 0
  [[ -t 0 ]] || die "$1 - needs confirmation; re-run interactively or pass --yes"
  local reply; read -r -p "$1 [y/N] " reply; [[ "$reply" =~ ^[Yy]([Ee][Ss])?$ ]]
}

# --- validate inputs
[[ "$TARGET" =~ ^[A-Za-z_][A-Za-z0-9_]{0,62}$ ]] || die "invalid target database name: $TARGET"
case $TARGET in postgres|template0|template1) die "refusing to restore into system database '$TARGET'" ;; esac
IS_LIVE=false; [[ "$TARGET" == "$LIVE_DATABASE" ]] && IS_LIVE=true
if $IS_LIVE && ! $FORCE_OVERWRITE; then
  die "target '$TARGET' is the LIVE database. Restore into a scratch database (default), or pass --force-overwrite for disaster recovery."
fi
$IS_LIVE && $DROP_AFTER && die "--drop-after can never be used with the live database"

if [[ -z "$FILE" ]]; then
  FILE=$(ls -1t "$BACKUP_DIR"/"${LIVE_DATABASE}"_*.dump 2>/dev/null | head -n1 || true)
  [[ -n "$FILE" ]] || die "no backups found in $BACKUP_DIR (run scripts/backup-db.sh first)"
fi
[[ -f "$FILE" ]] || die "backup file not found: $FILE"

project=$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "$PG_CONTAINER" 2>/dev/null) \
  || die "container $PG_CONTAINER not found (is the stack up?)"
[[ "$project" == "pgms" ]] || die "$PG_CONTAINER belongs to compose project '$project', not 'pgms' - refusing"

# sql <db>  (SQL on stdin, tuples-only output)
sql() { docker exec -i "$PG_CONTAINER" sh -c 'psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -X -q -At -F "|"' sh "$1"; }
db_exists() { [[ "$(echo "select 1 from pg_database where datname = '$1'" | sql postgres)" == "1" ]]; }

# --- integrity of the archive
if [[ -f "$FILE.sha256" ]]; then
  (cd "$(dirname "$FILE")" && { shasum -a 256 -c "$(basename "$FILE").sha256" 2>/dev/null || sha256sum -c "$(basename "$FILE").sha256"; } >/dev/null) \
    || die "checksum mismatch for $FILE"
  log "checksum OK"
fi
tables_in_archive=$(docker exec -i "$PG_CONTAINER" pg_restore --list < "$FILE" | grep -c ' TABLE DATA ' || true)
[[ "$tables_in_archive" -gt 0 ]] || die "$FILE is not a readable pg_dump custom archive"
log "backup: $FILE ($(du -h "$FILE" | cut -f1), $tables_in_archive tables with data)"
log "target: $TARGET $($IS_LIVE && echo '(LIVE DATABASE - OVERWRITE)' || echo '(scratch)')"

if $IS_LIVE; then
  echo
  echo "  !!! You are about to OVERWRITE the live database '$TARGET' with $(basename "$FILE")."
  echo "  !!! All changes made after that backup was taken will be LOST."
  echo
  others=$(echo "select count(*) from pg_stat_activity where datname = '$TARGET' and pid <> pg_backend_pid()" | sql postgres)
  if [[ "$others" != "0" ]]; then
    die "$others other session(s) are connected to '$TARGET'. Stop the writers first:
       docker compose -p pgms stop backend analytics
     then re-run. (Start them again afterwards with: docker compose -p pgms start backend analytics)"
  fi
  if [[ "${RESTORE_CONFIRM:-}" != "$TARGET" ]]; then
    [[ -t 0 ]] || die "overwrite needs confirmation: run interactively or set RESTORE_CONFIRM=$TARGET"
    read -r -p "  Type the database name ($TARGET) to continue: " typed
    [[ "$typed" == "$TARGET" ]] || die "confirmation did not match - nothing changed"
  fi
  log "taking a safety backup of the current '$TARGET' first"
  PG_DATABASE="$TARGET" "$ROOT/scripts/backup-db.sh" --no-prune >/dev/null
  log "safety backup written to $BACKUP_DIR"
  log "restoring (single transaction, --clean) ..."
  docker exec -i "$PG_CONTAINER" sh -c \
    'pg_restore -U "$POSTGRES_USER" -d "$1" --clean --if-exists --single-transaction --exit-on-error --no-password' \
    sh "$TARGET" < "$FILE" || die "pg_restore failed - the transaction was rolled back, '$TARGET' is unchanged"
else
  if db_exists "$TARGET"; then
    confirm "scratch database '$TARGET' already exists. Drop and recreate it?" || die "aborted - nothing changed"
    echo "drop database \"$TARGET\" with (force)" | sql postgres
    log "dropped existing scratch database $TARGET"
  fi
  echo "create database \"$TARGET\" template template0" | sql postgres
  log "created $TARGET; restoring ..."
  start=$(date +%s)
  if ! docker exec -i "$PG_CONTAINER" sh -c \
      'pg_restore -U "$POSTGRES_USER" -d "$1" --exit-on-error --no-password' sh "$TARGET" < "$FILE"; then
    die "pg_restore failed (scratch database '$TARGET' left in place for inspection)"
  fi
  log "restore finished in $(( $(date +%s) - start ))s"
fi

# --- verification: exact row counts per table, restored vs live
COUNT_SQL="select table_schema || '.' || table_name,
       (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name),
                                            false, true, '')))[1]::text::bigint
  from information_schema.tables
 where table_type = 'BASE TABLE' and table_schema not in ('pg_catalog', 'information_schema')
 order by 1"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
echo "$COUNT_SQL" | sql "$TARGET" > "$tmp/restored"
restored_tables=$(wc -l < "$tmp/restored" | tr -d ' ')
[[ "$restored_tables" -gt 0 ]] || die "verification failed: no tables in $TARGET"
flyway=$(echo "select version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1" | sql "$TARGET" 2>/dev/null || echo "?")

missing=0
echo
if ! $IS_LIVE; then
  echo "$COUNT_SQL" | sql "$LIVE_DATABASE" > "$tmp/live"
  printf '  %-45s %12s %12s  %s\n' "table" "restored" "live now" ""
  printf '  %-45s %12s %12s\n' "-----" "--------" "--------"
  # full outer join on table name
  awk -F'|' 'NR==FNR { r[$1]=$2; next } { l[$1]=$2 } END {
      for (t in r) all[t]=1; for (t in l) all[t]=1
      n = 0; for (t in all) keys[++n] = t
      for (i = 1; i <= n; i++) for (j = i + 1; j <= n; j++) if (keys[j] < keys[i]) { x = keys[i]; keys[i] = keys[j]; keys[j] = x }
      for (i = 1; i <= n; i++) { t = keys[i]
        rv = (t in r) ? r[t] : "MISSING"; lv = (t in l) ? l[t] : "-"
        note = (rv == "MISSING") ? "<- missing in restore" : (rv != lv && lv != "-") ? "(changed since backup)" : ""
        printf "  %-45s %12s %12s  %s\n", t, rv, lv, note }
    }' "$tmp/restored" "$tmp/live"
  missing=$(awk -F'|' 'NR==FNR { r[$1]=1; next } !($1 in r) { m++ } END { print m+0 }' "$tmp/restored" "$tmp/live")
  total_r=$(awk -F'|' '{ s += $2 } END { print s+0 }' "$tmp/restored")
  total_l=$(awk -F'|' '{ s += $2 } END { print s+0 }' "$tmp/live")
  echo
  log "verified: $restored_tables tables, $total_r rows restored (live now: $total_l rows), flyway version $flyway"
  [[ "$missing" -eq 0 ]] || log "WARNING: $missing table(s) of the live database are missing from the restore (newer migrations?)"
else
  awk -F'|' '{ printf "  %-45s %12s\n", $1, $2 }' "$tmp/restored"
  log "verified: $restored_tables tables in $TARGET, flyway version $flyway"
  log "start the writers again:  docker compose -p pgms start backend analytics"
fi

if $DROP_AFTER; then
  echo "drop database \"$TARGET\" with (force)" | sql postgres
  log "dropped scratch database $TARGET"
elif ! $IS_LIVE; then
  log "scratch database '$TARGET' kept. Inspect with: docker exec -it $PG_CONTAINER psql -U pgms_admin -d $TARGET"
  log "drop it when done:  docker exec $PG_CONTAINER psql -U pgms_admin -d postgres -c 'drop database \"$TARGET\"'"
fi
[[ "$missing" -eq 0 ]]
