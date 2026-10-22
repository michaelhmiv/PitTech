#!/bin/sh
# PitTech staging backup/restore drill (acceptance E1).
# One-shot: behavior is keyed on DRILL_MODE (dump | restore).
# Never prints DATABASE_URL or any derived URL.
set -eu

DUMP_FILE=/data/pittech-staging-drill.sql
LIST_TABLES="SELECT table_schema, table_name FROM information_schema.tables WHERE table_type='BASE TABLE' AND table_schema NOT IN ('pg_catalog','information_schema') ORDER BY 1,2"

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
    psql "$ADMIN_URL" -c "DROP DATABASE IF EXISTS drill_restore"
    psql "$ADMIN_URL" -c "CREATE DATABASE drill_restore"
    psql "$RESTORE_URL" -v ON_ERROR_STOP=1 -f "$DUMP_FILE"
    print_counts "$RESTORE_URL" "RESTORED_COUNT"
    psql "$ADMIN_URL" -c "DROP DATABASE drill_restore"
    echo "DRILL_DONE"
    ;;
  probe)
    # Prints SET/EMPTY booleans only -- never values.
    for name in DATABASE_URL DRILL_REF_A DRILL_REF_B DRILL_REF_C DRILL_REF_D DRILL_REF_E DRILL_REF_F; do
      eval "val=\${$name:-}"
      if [ -n "$val" ]; then echo "PROBE $name=SET"; else echo "PROBE $name=EMPTY"; fi
    done
    # If a password + private domain rendered, try listing database names
    # (names only; credentials are used in-container, never printed).
    pw="${DRILL_REF_B:-${DRILL_REF_F:-}}"
    if [ -n "$pw" ] && [ -n "${DRILL_REF_D:-}" ]; then
      probe_url="postgresql://postgres:${pw}@${DRILL_REF_D}:5432/postgres"
      psql "$probe_url" -At -c "SELECT 'DBNAME ' || datname FROM pg_database WHERE datistemplate=false ORDER BY 1" || echo "PROBE DBLIST=FAILED"
    fi
    ;;
  *)
    echo "DRILL_ERROR: DRILL_MODE must be 'dump' or 'restore'" >&2
    exit 1
    ;;
esac
