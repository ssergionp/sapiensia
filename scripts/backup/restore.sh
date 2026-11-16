#!/usr/bin/env bash
# NFR-008 ADR-0002: restores an encrypted backup (.age) into an empty target database.
#
# Usage: restore.sh <file.age> <age private key file>
# Environment: PGHOST PGPORT PGUSER PGPASSWORD PGDATABASE (target), PG_MAJOR, PG_DOCKER_ARGS (CI: --network host).
# The file is decrypted first, so a wrong key or a corrupted file stops the script before the target is touched.
# The decrypted dump lives only in a temporary directory that is deleted when the script ends.
# rls_auto_enable and the public schema are left out of the restore: every Supabase project already has them.
set -euo pipefail

backup="${1:?usage: restore.sh <file.age> <key file>}"
key="${2:?usage: restore.sh <file.age> <key file>}"
: "${PGHOST:?}" "${PGPORT:?}" "${PGUSER:?}" "${PGPASSWORD:?}" "${PGDATABASE:?}" "${PG_MAJOR:?}"
export PGHOST PGPORT PGUSER PGPASSWORD PGDATABASE

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
tmp_for_docker="$(cygpath -m "$tmp" 2>/dev/null || echo "$tmp")"

# shellcheck disable=SC2086
pgc() { # the postgres client from the image that matches the server, with the temporary directory at /work
  MSYS_NO_PATHCONV=1 docker run --rm ${PG_DOCKER_ARGS-} -v "$tmp_for_docker:/work" \
    -e PGHOST -e PGPORT -e PGUSER -e PGPASSWORD -e PGDATABASE -e PGSSLMODE "postgres:$PG_MAJOR" "$@"
}

age -d -i "$key" -o "$tmp/backup.dump" "$backup"

pgc pg_restore --list /work/backup.dump | grep -Ev 'rls_auto_enable| SCHEMA - public ' > "$tmp/restore.list"

# single transaction: if anything fails, the target stays as it was
pgc pg_restore --no-owner --no-privileges --exit-on-error --single-transaction \
  --use-list /work/restore.list --dbname "$PGDATABASE" /work/backup.dump

echo "Restored into database $PGDATABASE. Tables and exact row counts:"
pgc psql -X -At -F ' | ' -c "
  select table_name, (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text
  from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE' order by 1"
