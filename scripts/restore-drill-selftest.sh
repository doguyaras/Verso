#!/usr/bin/env bash
# Self-test of backup + restore drill against the running compose stack (CI: .github/workflows/restore-drill.yml).
# A drill that never fails proves nothing: this script shows it passes on real data and fails on every tampering.
#
#   docker compose up -d --build --wait && bash scripts/restore-drill-selftest.sh
#
# 1. Data: a probe table with a vector column (migration role) and rows written by the application role.
# 2. Backup: encrypted at rest (AES256, no plaintext dump header); the drill restores exactly the probe rows and
#    Flyway validate really runs.
# 3. Each of these must fail: changed row count, changed privilege fingerprint, a restore that really loses its grants
#    (pg_restore --no-privileges), a backup whose application role may run DDL, an applied migration unknown to this
#    checkout, a flipped byte in the encrypted dump, a missing checksum.
# 4. A backup taken under concurrent writes restores consistently (manifest and dump share one snapshot).
# The probe table, the history probe row and every self-test file are removed at the end, pass or fail.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
ROWS=250
PROBE=document.restore_selftest_probe
PHANTOM_VERSION=999999
failures=0

# as_role <role> <secret> <sql>: over the network interface (host "postgres"), so pg_hba asks for the password;
# 127.0.0.1 inside the container is trusted by the image and would accept any password (phase 2 security review).
as_role() {
  docker compose exec -T postgres bash -c \
    "PGPASSWORD=\"\$(cat /run/secrets/$2)\" psql -h postgres -U $1 -d verso -X -q -t -A -v ON_ERROR_STOP=1 -c \"$3\""
}
in_backups() { docker compose run --rm --no-deps -T --entrypoint bash backup -c "$1" 2>/dev/null; }

cleanup() {
  as_role svc_document_migrate SECRET_DB_DOCUMENT_MIGRATE_PASSWORD "DROP TABLE IF EXISTS $PROBE" >/dev/null 2>&1 || true
  as_role svc_document_migrate SECRET_DB_DOCUMENT_MIGRATE_PASSWORD \
    "REVOKE CREATE ON SCHEMA document FROM svc_document" >/dev/null 2>&1 || true
  as_role svc_document_migrate SECRET_DB_DOCUMENT_MIGRATE_PASSWORD \
    "DELETE FROM document.flyway_schema_history WHERE version = '$PHANTOM_VERSION'" >/dev/null 2>&1 || true
  in_backups 'rm -f /backups/verso-selftest-*' >/dev/null 2>&1 || true
}
trap cleanup EXIT

ok() { echo "ok    $1"; }
wrong() { echo "WRONG $1"; failures=$((failures + 1)); }

# backup_as <name>: one fresh backup, copied as verso-selftest-<name>.* with its own checksum.
backup_as() {
  local line file
  line=$(docker compose run --rm --no-deps -T backup once 2>/dev/null | grep 'backup: ok' | tail -1)
  [ -n "$line" ] || { wrong "backup did not succeed ($1)"; return 1; }
  file=$(sed -E 's/.*file=([^ ]+).*/\1/' <<<"$line")
  in_backups "cd /backups && cp $file verso-selftest-$1.dump.gpg && cp ${file%.dump.gpg}.manifest verso-selftest-$1.manifest \
    && cp ${file%.dump.gpg}.acl verso-selftest-$1.acl && sha256sum verso-selftest-$1.dump.gpg > verso-selftest-$1.dump.gpg.sha256"
}

# copy_backup <name>: the good backup under another name, checksum rewritten for the copy.
copy_backup() {
  in_backups "cd /backups && cp verso-selftest-good.dump.gpg verso-selftest-$1.dump.gpg \
    && cp verso-selftest-good.manifest verso-selftest-$1.manifest && cp verso-selftest-good.acl verso-selftest-$1.acl \
    && sha256sum verso-selftest-$1.dump.gpg > verso-selftest-$1.dump.gpg.sha256"
}

expect() { # expect pass|fail <label> <BACKUP_FILE> <pattern> [RESTORE_OPTIONS]
  local want=$1 label=$2 file=$3 pattern=$4 options=${5:-} out rc=0
  out=$(BACKUP_FILE="$file" RESTORE_OPTIONS="$options" bash scripts/restore-drill.sh 2>&1) || rc=$?
  if [ "$want" = pass ] && [ $rc -eq 0 ] && grep -q "$pattern" <<<"$out"; then
    ok "$label"
  elif [ "$want" = fail ] && [ $rc -ne 0 ] && grep -q "$pattern" <<<"$out"; then
    ok "$label (drill failed as it must: $(grep -oE 'FAILED: .*|Validate failed.*' <<<"$out" | head -1))"
  else
    wrong "$label (exit $rc)"; grep -E 'restore-check|restore-drill|ERROR|Validate' <<<"$out" | tail -6 | sed 's/^/      /'
  fi
}

cleanup
as_role svc_document_migrate SECRET_DB_DOCUMENT_MIGRATE_PASSWORD \
  "CREATE TABLE $PROBE (id int PRIMARY KEY, embedding extensions.vector(3))" >/dev/null
as_role svc_document SECRET_DB_DOCUMENT_PASSWORD \
  "INSERT INTO $PROBE SELECT g, ARRAY[g, g * 2, g * 3]::real[]::extensions.vector FROM generate_series(1, $ROWS) g" >/dev/null

backup_as good && ok "backup taken"

# Encrypted at rest: an AES256 symmetric packet, and no plaintext pg_dump header (test review T2).
if in_backups "export GNUPGHOME=/tmp/g && mkdir -m 700 -p \$GNUPGHOME && cd /backups \
    && ! grep -q PGDMP verso-selftest-good.dump.gpg \
    && gpg --batch --list-packets verso-selftest-good.dump.gpg 2>/dev/null | grep -q '^:symkey enc packet: version [0-9]*, cipher 9,'"; then
  ok "backup is AES256-encrypted at rest"
else wrong "backup is not AES256-encrypted"; fi

# The probe's own manifest line: a total of all tables breaks as soon as another table holds rows (test review T5).
if in_backups "grep -qx 'document.restore_selftest_probe|$ROWS' /backups/verso-selftest-good.manifest"; then
  ok "manifest holds the $ROWS probe rows"
else wrong "manifest does not hold the $ROWS probe rows"; fi

expect pass "drill restores rows, privileges and grants" verso-selftest-good.dump.gpg "app_readable_tables=1"
expect pass "drill runs Flyway validate" verso-selftest-good.dump.gpg "Successfully validated"

copy_backup count
in_backups "cd /backups && sed -i -E 's/^(document\.restore_selftest_probe)\|$ROWS$/\1|$((ROWS + 1))/' verso-selftest-count.manifest"
expect fail "changed row count is detected" verso-selftest-count.dump.gpg "FAILED: row counts"

copy_backup acl
in_backups "cd /backups && sed -i -E 's/svc_document=arwd/svc_document=r/' verso-selftest-acl.acl"
expect fail "changed privilege fingerprint is detected" verso-selftest-acl.dump.gpg "FAILED: privileges"

expect fail "restore that loses its grants is detected" verso-selftest-good.dump.gpg "FAILED: privileges" --no-privileges

# A backup in which the application role may run DDL must fail (test review R29).
as_role svc_document_migrate SECRET_DB_DOCUMENT_MIGRATE_PASSWORD "GRANT CREATE ON SCHEMA document TO svc_document" >/dev/null
backup_as ddl || true
as_role svc_document_migrate SECRET_DB_DOCUMENT_MIGRATE_PASSWORD "REVOKE CREATE ON SCHEMA document FROM svc_document" >/dev/null
expect fail "application role with DDL rights after restore is detected" verso-selftest-ddl.dump.gpg "could run DDL"

# An applied migration this checkout does not have: Flyway validate must refuse it (test review T4).
as_role svc_document_migrate SECRET_DB_DOCUMENT_MIGRATE_PASSWORD "INSERT INTO document.flyway_schema_history
  (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
  VALUES ((SELECT coalesce(max(installed_rank), 0) + 1 FROM document.flyway_schema_history), '$PHANTOM_VERSION',
          'phantom', 'SQL', 'V${PHANTOM_VERSION}__phantom.sql', 0, 'selftest', 0, true)" >/dev/null
backup_as phantom || true
as_role svc_document_migrate SECRET_DB_DOCUMENT_MIGRATE_PASSWORD \
  "DELETE FROM document.flyway_schema_history WHERE version = '$PHANTOM_VERSION'" >/dev/null
expect fail "applied migration unknown to this checkout is detected" verso-selftest-phantom.dump.gpg "Validate failed"

copy_backup byte
in_backups "cd /backups && printf 'X' | dd of=verso-selftest-byte.dump.gpg bs=1 seek=200 count=1 conv=notrunc status=none"
expect fail "corrupted encrypted file is detected" verso-selftest-byte.dump.gpg "FAILED: checksum mismatch"

copy_backup nosum
in_backups "cd /backups && rm verso-selftest-nosum.dump.gpg.sha256"
expect fail "missing checksum is detected" verso-selftest-nosum.dump.gpg "FAILED: checksum, manifest or acl missing"

# Writes during the backup: manifest, fingerprint and dump come from one snapshot (test review R31).
docker compose exec -T postgres bash -c "for i in \$(seq 1 3000); do echo \"INSERT INTO $PROBE SELECT max(id) + 1, '[0,0,0]' FROM $PROBE; SELECT pg_sleep(0.002);\"; done \
  | PGPASSWORD=\"\$(cat /run/secrets/SECRET_DB_DOCUMENT_PASSWORD)\" psql -h postgres -U svc_document -d verso -X -q -v ON_ERROR_STOP=1 >/dev/null" &
writer=$!
backup_as busy || true
writer_rc=0
wait $writer || writer_rc=$?
[ $writer_rc -eq 0 ] || wrong "concurrent writer failed (exit $writer_rc)"
expect pass "backup under concurrent writes restores consistently" verso-selftest-busy.dump.gpg "restore-check: ok"

if [ $failures -eq 0 ]; then echo "restore-drill-selftest: OK"; else echo "restore-drill-selftest: $failures WRONG"; exit 1; fi
