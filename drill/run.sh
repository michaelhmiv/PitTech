#!/bin/sh
# PitTech staging backup/restore drill (acceptance E1).
# One-shot: behavior is keyed on DRILL_MODE (dump | restore).
# Never prints DATABASE_URL or any derived URL.
set -eu

DUMP_FILE=/data/pittech-staging-drill.sql
LIST_TABLES="SELECT table_schema || '.' || table_name FROM information_schema.tables WHERE table_type='BASE TABLE' AND table_schema NOT IN ('pg_catalog','information_schema') ORDER BY 1"

print_counts() {
  # $1 = connection URL, $2 = label prefix
  psql "$1" -At -F'|' -c "$LIST_TABLES" | while IFS='|' read -r schema table; do
    count=$(psql "$1" -At -c "SELECT count(*) FROM \"$schema\".\"$table\"")
    echo "$2 $schema.$table=$count"
  done
}

case "${DRILL_MODE:-}" in
  dump)
    pg_dump "$DATABASE_URL" -f "$DUMP_FILE"
    print_counts "$DATABASE_URL" "SOURCE_COUNT"
    echo "DUMP_BYTES=$(wc -c < "$DUMP_FILE" | tr -d ' ')"
    ;;
  restore)
    ADMIN_URL="${DATABASE_URL%/*}/postgres"
    RESTORE_URL="${DATABASE_URL%/*}/drill_restore"
    exists=$(psql "$ADMIN_URL" -At -c "SELECT 1 FROM pg_database WHERE datname='drill_restore'")
    if [ "$exists" != "1" ]; then
      psql "$ADMIN_URL" -c "CREATE DATABASE drill_restore"
    fi
    psql "$RESTORE_URL" -v ON_ERROR_STOP=1 -f "$DUMP_FILE"
    print_counts "$RESTORE_URL" "RESTORED_COUNT"
    psql "$ADMIN_URL" -c "DROP DATABASE drill_restore"
    echo "DRILL_DONE"
    ;;
  *)
    echo "DRILL_ERROR: DRILL_MODE must be 'dump' or 'restore'" >&2
    exit 1
    ;;
esac
