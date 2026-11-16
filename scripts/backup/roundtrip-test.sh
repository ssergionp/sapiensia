#!/usr/bin/env bash
# NFR-008 ADR-0002: round trip test of the encrypted backup (dump, encrypt, decrypt, restore, compare).
# Runs the same backup.sh and restore.sh used in production, against the database in PGHOST/PGPORT, which
# must be a throwaway database (CI e2e job or `just db`): the test creates and drops rt_* tables, the
# rls_auto_enable function, the backup_reader role and the restore_check database.
# Defaults are for the local `just db` on Windows (Git Bash); CI sets PGHOST=localhost PGPORT=5432.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]]; then
  default_host=host.docker.internal
  default_docker_args=""
else
  default_host=localhost
  default_docker_args="--network host"
fi
export PGHOST="${PGHOST:-$default_host}"
export PGPORT="${PGPORT:-5433}"
export PG_MAJOR="${PG_MAJOR:-18}"
export PG_DOCKER_ARGS="${PG_DOCKER_ARGS-$default_docker_args}"
ADMIN_USER="${ADMIN_PGUSER:-app}"
ADMIN_PASSWORD="${ADMIN_PGPASSWORD:-app}"
SOURCE_DB="${PGDATABASE:-app}"

work="$(mktemp -d)"
mkdir -p "$work/tmp" "$work/out" "$work/failed-out" "$work/bin"
failures=0
BACKUP_ROLE_PASSWORD="$(openssl rand -hex 16)"
export BACKUP_ROLE_PASSWORD

pass() { echo "PASS $1"; }
fail() { echo "FAIL $1: $2"; failures=$((failures + 1)); }

# psql as the database owner (setup and comparison), through the same postgres image the backup uses
# shellcheck disable=SC2086
admin_psql() {
  MSYS_NO_PATHCONV=1 PGOPTIONS="-c client_min_messages=warning" PGUSER="$ADMIN_USER" PGPASSWORD="$ADMIN_PASSWORD" \
    docker run --rm -i $PG_DOCKER_ARGS -e PGHOST -e PGPORT -e PGUSER -e PGPASSWORD -e PGDATABASE -e PGOPTIONS \
    -e BACKUP_ROLE_PASSWORD "postgres:$PG_MAJOR" psql -X -q -v ON_ERROR_STOP=1 "$@"
}

cleanup() {
  PGDATABASE="$SOURCE_DB" admin_psql -c "drop table if exists rt_rls, rt_denied, rt_late;
    drop function if exists public.rls_auto_enable();
    drop owned by backup_reader; drop role if exists backup_reader;" >/dev/null 2>&1 || true
  PGDATABASE=postgres admin_psql -c "drop database if exists restore_check" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

# age and age-keygen: installed from the pinned release when not on the PATH
if ! command -v age-keygen >/dev/null 2>&1; then
  bash "$here/install-age.sh" "$work/bin" >/dev/null
  PATH="$work/bin:$PATH"
fi

snapshot() { # exact row count per public table, sequence values and the last Flyway version
  PGDATABASE="$1" admin_psql -At -c "
    select 'table ' || table_name || ' ' ||
      (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text
      from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'
    union all select 'sequence ' || sequencename || ' ' || coalesce(last_value::text, 'null') from pg_sequences where schemaname = 'public'
    order by 1"
  # the Flyway history only exists after a restore (the empty target has none)
  if [[ "$(PGDATABASE="$1" admin_psql -At -c "select to_regclass('public.flyway_schema_history') is not null")" == t ]]; then
    PGDATABASE="$1" admin_psql -At -c "select 'flyway ' || coalesce(version, 'none')
      from flyway_schema_history where success order by installed_rank desc limit 1"
  fi
}

run_backup() { # $1 = out dir; the rest are environment assignments for this run only
  local out="$1"
  shift
  env PGUSER=backup_reader PGPASSWORD="$BACKUP_ROLE_PASSWORD" PGDATABASE="$SOURCE_DB" \
    BACKUP_RECIPIENTS_FILE="$work/right.pub" BACKUP_OUT_DIR="$out" BACKUP_SKIP_UPLOAD=1 TMPDIR="$work/tmp" \
    "$@" bash "$here/backup.sh"
}

# ---- setup: role, table created after the grant, RLS table, Supabase-like function, two key pairs ----
PGDATABASE="$SOURCE_DB" admin_psql -f - < "$here/create-backup-role.sql" >/dev/null
PGDATABASE="$SOURCE_DB" admin_psql -c "
  create table rt_late (id serial primary key, note text);
  insert into rt_late (note) select 'late ' || g from generate_series(1, 7) g;
  create table rt_rls (id serial primary key, note text);
  insert into rt_rls (note) select 'row ' || g from generate_series(1, 5) g;
  alter table rt_rls enable row level security;
  create function public.rls_auto_enable() returns void language sql as 'select 1';" >/dev/null
age-keygen -o "$work/right.key" 2>/dev/null
age-keygen -y "$work/right.key" > "$work/right.pub"
age-keygen -o "$work/wrong.key" 2>/dev/null

# ---- NFR-008: a table the backup role cannot read makes the dump fail ----
PGDATABASE="$SOURCE_DB" admin_psql -c "create table rt_denied (id int); insert into rt_denied values (1);
  revoke all on rt_denied from backup_reader;" >/dev/null
title="NFR-008 dump fails loudly when the backup role cannot read a table"
if run_backup "$work/failed-out" >"$work/denied.log" 2>&1; then
  fail "$title" "backup.sh exited 0"
elif [[ -n "$(ls -A "$work/failed-out")" ]]; then
  fail "$title" "a file was left in the output directory"
else
  pass "$title"
fi
PGDATABASE="$SOURCE_DB" admin_psql -c "drop table rt_denied" >/dev/null

# ---- NFR-008: a failed pg_dump (closed port) produces no file and a non-zero exit ----
title="NFR-008 a failed pg_dump produces no file and a non-zero exit"
if run_backup "$work/failed-out" PGPORT=1 >"$work/closed.log" 2>&1; then
  fail "$title" "backup.sh exited 0"
elif [[ -n "$(ls -A "$work/failed-out")" ]]; then
  fail "$title" "a file was left in the output directory"
else
  pass "$title"
fi

# ---- NFR-008: a dump cut off midway (more than 1 KiB, then pg_dump fails) produces no file ----
# A stand-in `docker` on the PATH emits 4 KiB for pg_dump and then fails; every other call goes to the real docker.
# Only pipefail (not the size check) can catch this: the truncated output is a valid age file above the minimum size.
real_docker="$(command -v docker)"
cat > "$work/bin/docker" <<'STUB'
#!/usr/bin/env bash
if [[ " $* " == *" pg_dump "* ]]; then
  head -c 4096 /dev/zero
  echo "simulated connection loss" >&2
  exit 1
fi
exec "$REAL_DOCKER" "$@"
STUB
chmod +x "$work/bin/docker"
title="NFR-008 a dump cut off midway produces no file and a non-zero exit"
if run_backup "$work/failed-out" PATH="$work/bin:$PATH" REAL_DOCKER="$real_docker" >"$work/cut.log" 2>&1; then
  fail "$title" "backup.sh exited 0"
elif [[ -n "$(ls -A "$work/failed-out")" ]]; then
  fail "$title" "a file was left in the output directory"
else
  pass "$title"
fi
rm -f "$work/bin/docker"

# ---- the real backup, without the upload ----
backup_file="$(run_backup "$work/out" 2>"$work/backup.log" | tail -n 1)" || backup_file=""

title="NFR-008 backup file is age-encrypted and has no plaintext dump marker"
if [[ -z "$backup_file" || ! -f "$backup_file" ]]; then
  fail "$title" "backup file does not exist (backup.sh printed '${backup_file}')"
elif [[ "$(head -c 21 "$backup_file")" != "age-encryption.org/v1" ]]; then
  fail "$title" "file does not start with the age header"
elif grep -qa PGDMP "$backup_file"; then
  fail "$title" "file contains the PGDMP marker"
elif [[ "$(wc -c < "$backup_file")" -lt 1024 ]]; then
  fail "$title" "file is smaller than 1 KiB"
else
  pass "$title"
fi

# ---- restore target: a new database that already has rls_auto_enable, like a new Supabase project ----
PGDATABASE=postgres admin_psql -c "drop database if exists restore_check" >/dev/null
PGDATABASE=postgres admin_psql -c "create database restore_check" >/dev/null
PGDATABASE=restore_check admin_psql -c "create function public.rls_auto_enable() returns void language sql as 'select 1'" >/dev/null
restore() { # $1 = key file
  PGUSER="$ADMIN_USER" PGPASSWORD="$ADMIN_PASSWORD" PGDATABASE=restore_check TMPDIR="$work/tmp" \
    bash "$here/restore.sh" "$backup_file" "$1"
}

title="NFR-008 restore with the wrong key fails and leaves the target untouched"
before="$(snapshot restore_check)"
if [[ ! -f "$backup_file" ]]; then
  fail "$title" "no backup file to restore"
elif restore "$work/wrong.key" >"$work/wrongkey.log" 2>&1; then
  fail "$title" "restore.sh exited 0 with the wrong key"
elif [[ "$(snapshot restore_check)" != "$before" ]]; then
  fail "$title" "the target database changed"
else
  pass "$title"
fi

title="NFR-008 ADR-0002 round trip restores every public table with the same row counts"
if [[ ! -f "$backup_file" ]]; then
  fail "$title" "no backup file to restore"
elif ! restore "$work/right.key" >"$work/restore.log" 2>&1; then
  fail "$title" "restore.sh failed: $(tail -n 5 "$work/restore.log" | tr '\n' ' ')"
else
  snapshot "$SOURCE_DB" > "$work/source.txt"
  snapshot restore_check > "$work/target.txt"
  if [[ "$(wc -l < "$work/source.txt")" -lt 10 ]]; then
    fail "$title" "the source snapshot is too small to prove anything"
  elif ! diff "$work/source.txt" "$work/target.txt" >"$work/diff.txt"; then
    fail "$title" "source and target differ: $(head -n 5 "$work/diff.txt" | tr '\n' ' ')"
  elif ! grep -qx 'table rt_late 7' "$work/target.txt"; then
    fail "$title" "the table created after the grant is missing or incomplete"
  else
    pass "$title"
  fi
fi

title="NFR-008 dump of a table with row level security is complete or fails"
if grep -qx 'table rt_rls 5' "$work/target.txt" 2>/dev/null; then
  pass "$title"
else
  fail "$title" "rt_rls does not have its 5 rows in the restored database"
fi

# ---- ADR-0002: object keys (pure function of the script) ----
title="ADR-0002 object key carries date and time and the monthly copy is made by the first upload of each month"
keys() { (source "$here/backup.sh"; object_keys "$1" 2>&1 | tr '\n' ' ') || true; }
morning="$(keys 2026-12-01T07:17:00Z)"
other="$(keys 2026-12-02T19:17:00Z)"
if [[ "$morning" != "daily/2026-12-01T0717Z.dump.age monthly/2026-12-01T0717Z.dump.age " ]]; then
  fail "$title" "day 1 gave '$morning'"
elif [[ "$other" != "daily/2026-12-02T1917Z.dump.age monthly/2026-12-02T1917Z.dump.age " ]]; then
  fail "$title" "day 2 gave '$other'"
else
  pass "$title"
fi

# ---- ADR-0002: the upload path, with a stand-in `aws` on the PATH ----
# The stand-in logs one line per call, answers head-object with the size of the file and answers the
# monthly list with STUB_MONTHLY_KEY ("None" = no monthly object yet, like R2 with --query Contents[0].Key).
cat > "$work/bin/aws" <<'STUB'
#!/usr/bin/env bash
case "$*" in
  "s3 cp "*) echo "cp ${*: -1}" >> "$STUB_LOG" ;;
  "s3api list-objects-v2 "*" --prefix monthly/2026-12 "*) echo "list" >> "$STUB_LOG"; echo "${STUB_MONTHLY_KEY:-None}" ;;
  "s3api head-object "*) echo "head" >> "$STUB_LOG"; wc -c < "$STUB_FILE" ;;
esac
STUB
chmod +x "$work/bin/aws"
upload_run() { # $1 = monthly key already in the bucket ("None" = none); prints the cp lines of the run
  local log="$work/aws-calls.log" out="$work/upload-out"
  rm -rf "$out"; mkdir -p "$out"; : > "$log"
  run_backup "$out" BACKUP_SKIP_UPLOAD=0 BACKUP_NOW=2026-12-02T07:17:00Z STUB_LOG="$log" STUB_MONTHLY_KEY="$1" \
    R2_ACCOUNT_ID=acct R2_BUCKET=bucket AWS_ACCESS_KEY_ID=ak AWS_SECRET_ACCESS_KEY=sk \
    STUB_FILE="$out/2026-12-02T0717Z.dump.age" PATH="$work/bin:$PATH" >"$work/upload.log" 2>&1 || return 1
  grep '^cp ' "$log" | tr '\n' ' '
}
title="ADR-0002 the first upload of a month also writes the monthly copy, even when it is not day 1"
calls="$(upload_run None)" || calls="backup.sh failed: $(tail -n 3 "$work/upload.log" | tr '\n' ' ')"
if [[ "$calls" != "cp s3://bucket/daily/2026-12-02T0717Z.dump.age cp s3://bucket/monthly/2026-12-02T0717Z.dump.age " ]]; then
  fail "$title" "got '$calls'"
else
  pass "$title"
fi
title="ADR-0002 a later upload of the same month only writes the daily copy"
calls="$(upload_run monthly/2026-12-01T0717Z.dump.age)" || calls="backup.sh failed: $(tail -n 3 "$work/upload.log" | tr '\n' ' ')"
if [[ "$calls" != "cp s3://bucket/daily/2026-12-02T0717Z.dump.age " ]]; then
  fail "$title" "got '$calls'"
else
  pass "$title"
fi
rm -f "$work/bin/aws"

# ---- NFR-008: nothing in plain text is left behind ----
title="NFR-008 no plaintext dump is left on disk after backup and restore"
leftovers="$(find "$work/tmp" -type f | head -n 3)"
marked="$(grep -rlaF PGDMP "$work" 2>/dev/null | grep -v '\.age$' | head -n 3 || true)"
other_files="$(find "$work/out" -type f ! -name '*.age' | head -n 3)"
if [[ ! -f "$backup_file" ]]; then
  fail "$title" "no backup was made, so nothing was checked"
elif [[ -n "$leftovers$marked$other_files" ]]; then
  fail "$title" "left behind: $leftovers $marked $other_files"
else
  pass "$title"
fi

if [[ "$failures" -ne 0 ]]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "All checks passed"
