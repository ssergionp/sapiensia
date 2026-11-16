#!/usr/bin/env bash
# NFR-008 ADR-0002: encrypted backup of the public schema (pg_dump | age), optionally sent to Cloudflare R2.
#
# The plain text dump exists only inside the pipe: pg_dump | age. Nothing is uploaded if any step fails,
# and the output file only gets its final name after the checks pass.
#
# Environment (no secret goes on a command line):
#   PGHOST PGPORT PGUSER PGPASSWORD PGDATABASE   source database (the read-only role backup_reader)
#   PG_MAJOR                                     major version of the server; pg_dump comes from postgres:$PG_MAJOR
#   PG_DOCKER_ARGS                               extra docker run arguments (CI: --network host)
#   BACKUP_RECIPIENTS_FILE                       file with the public age keys (age1...)
#   BACKUP_OUT_DIR                               directory for the .age file
#   R2_ACCOUNT_ID R2_BUCKET AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY   upload (not needed with BACKUP_SKIP_UPLOAD=1)
#   BACKUP_SKIP_UPLOAD=1                         skips the upload
#   BACKUP_NOW                                   ISO time (2026-12-01T07:17:00Z), for tests only
# The last line printed is the path of the .age file.
set -euo pipefail

# Prints the daily key and the monthly key of this run: daily/<date>T<HHMM>Z.dump.age and monthly/<same name>.
# The upload writes the monthly one only when the bucket has no monthly/<YYYY-MM> object yet (the first
# successful upload of each month), so a delayed or dropped run does not lose the month's copy.
# The time in the name keeps a second run of the day from overwriting a locked object.
object_keys() { # $1 = now, ISO (2026-12-01T07:17:00Z)
  local now="$1" name
  name="${now:0:10}T${now:11:2}${now:14:2}Z.dump.age"
  echo "daily/$name"
  echo "monthly/$name"
}

# shellcheck disable=SC2086
pgc() { # a command of the postgres client, run from the image that matches the server
  MSYS_NO_PATHCONV=1 docker run --rm ${PG_DOCKER_ARGS-} \
    -e PGHOST -e PGPORT -e PGUSER -e PGPASSWORD -e PGDATABASE -e PGSSLMODE "postgres:$PG_MAJOR" "$@"
}

main() {
  : "${PGHOST:?}" "${PGPORT:?}" "${PGUSER:?}" "${PGPASSWORD:?}" "${PGDATABASE:?}" "${PG_MAJOR:?}"
  : "${BACKUP_RECIPIENTS_FILE:?}" "${BACKUP_OUT_DIR:?}"
  export PGHOST PGPORT PGUSER PGPASSWORD PGDATABASE

  # An empty or invalid recipients file stops here: the backup is never sent in plain text instead.
  if ! grep -Eq '^age1[02-9ac-hj-np-z]{58}$' "$BACKUP_RECIPIENTS_FILE"; then
    echo "backup.sh: no valid age public key (age1...) in $BACKUP_RECIPIENTS_FILE" >&2
    return 1
  fi

  server_version_num="$(pgc psql -X -At -c 'show server_version_num')"
  server_major="$((server_version_num / 10000))"
  if [[ "$server_major" != "$PG_MAJOR" ]]; then
    echo "backup.sh: the server is PostgreSQL $server_major but PG_MAJOR is $PG_MAJOR; set PG_MAJOR to $server_major" >&2
    return 1
  fi

  now="${BACKUP_NOW:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
  mapfile -t keys < <(object_keys "$now")
  file="$BACKUP_OUT_DIR/$(basename "${keys[0]}")"
  partial="$file.partial"
  trap 'rm -f "$partial"' EXIT

  # pipefail: a pg_dump that fails halfway (permission denied, connection lost) fails the whole pipe
  pgc pg_dump --format=custom --schema=public --no-owner --no-privileges \
    | age -R "$BACKUP_RECIPIENTS_FILE" -o "$partial"

  if [[ "$(head -c 21 "$partial")" != "age-encryption.org/v1" ]]; then
    echo "backup.sh: the output is not an age file" >&2
    return 1
  fi
  size="$(wc -c < "$partial")"
  if [[ "$size" -lt 1024 ]]; then
    echo "backup.sh: the output has only $size bytes" >&2
    return 1
  fi
  mv "$partial" "$file"
  echo "backup.sh: encrypted backup written: $(basename "$file") ($size bytes)" >&2

  if [[ "${BACKUP_SKIP_UPLOAD:-}" != "1" ]]; then
    upload "$file" "$size" "${keys[@]}" "${now:0:7}"
  fi
  echo "$file"
}

upload() { # $1 = file, $2 = size in bytes, $3 = daily key, $4 = monthly key, $5 = month (YYYY-MM)
  local file="$1" size="$2" month="$5" key remote_size existing
  local -a to_send=("$3")
  : "${R2_ACCOUNT_ID:?}" "${R2_BUCKET:?}" "${AWS_ACCESS_KEY_ID:?}" "${AWS_SECRET_ACCESS_KEY:?}"
  export AWS_REQUEST_CHECKSUM_CALCULATION=when_required AWS_RESPONSE_CHECKSUM_VALIDATION=when_required
  local endpoint="https://$R2_ACCOUNT_ID.r2.cloudflarestorage.com"
  # the first successful upload of the month also becomes the monthly copy
  existing="$(aws s3api list-objects-v2 --endpoint-url "$endpoint" --region auto --bucket "$R2_BUCKET" \
    --prefix "monthly/$month" --max-keys 1 --query 'Contents[0].Key' --output text)"
  if [[ -z "$existing" || "$existing" == "None" ]]; then
    to_send+=("$4")
  fi
  for key in "${to_send[@]}"; do
    aws s3 cp --only-show-errors --endpoint-url "$endpoint" --region auto "$file" "s3://$R2_BUCKET/$key"
    remote_size="$(aws s3api head-object --endpoint-url "$endpoint" --region auto --bucket "$R2_BUCKET" --key "$key" --query ContentLength --output text)"
    if [[ "$remote_size" != "$size" ]]; then
      echo "backup.sh: $key has $remote_size bytes in R2, the local file has $size" >&2
      return 1
    fi
    echo "backup.sh: uploaded $key ($size bytes)" >&2
  done
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
