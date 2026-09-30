#!/usr/bin/env bash
# Runs the backend SQL tests against a throwaway local PostgreSQL database:
#   supabase/tests/run.sh
# Needs a local PostgreSQL 16 server (psql as the postgres user) and write
# access to its extension directory for the pg_net / pg_cron stand-ins.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
ext="$(pg_config --sharedir 2>/dev/null || echo /usr/share/postgresql/16)/extension"
for x in pg_net pg_cron; do
  cmp -s "$here/ext/$x--0.1.sql" "$ext/$x--0.1.sql" || cp "$here/ext/$x.control" "$here/ext/$x--0.1.sql" "$ext/"
done
db="koode_test_$$"
psql_() { su postgres -c "psql -v ON_ERROR_STOP=1 -q -X $*"; }
su postgres -c "createdb $db"
trap 'su postgres -c "dropdb --if-exists $db"' EXIT
psql_ "-d $db -f '$here/supabase_stubs.sql'"
psql_ "-d $db -f '$here/../schema.sql'" 2>&1 | grep -v "^NOTICE" || true
psql_ "-d $db -f '$here/../schema.sql'" 2>&1 | grep -v "^NOTICE" || true   # re-run: must be idempotent
for t in "$here"/test_*.sql; do
  echo "== $(basename "$t")"
  psql_ "-d $db -f '$t'"
done
echo "All backend SQL tests passed."
