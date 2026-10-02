#!/usr/bin/env bash
# Self-test of backup + restore drill against the running compose stack (CI: .github/workflows/restore-drill.yml).
# A drill that never fails proves nothing: this script shows it passes on real data and fails on every tampering.
#
#   docker compose up -d --build --wait && bash scripts/restore-drill-selftest.sh
#
# 1. Data: a probe table with a vector column (migration role) and rows written by the application role.
# 2. Backup, then the drill must pass and report exactly those rows.
# 3. Each of these must fail: changed row count in the manifest, changed privilege fingerprint, a restore that really
#    loses its grants (pg_restore --no-privileges, review D1), flipped byte in the encrypted dump, missing checksum.
# The probe table and every self-test file are removed at the end, pass or fail.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
ROWS=250
PROBE=document.restore_selftest_probe
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
  in_backups 'rm -f /backups/verso-selftest-*' >/dev/null 2>&1 || true
}
trap cleanup EXIT

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
    echo "ok    $label"
  elif [ "$want" = fail ] && [ $rc -ne 0 ] && grep -q "$pattern" <<<"$out"; then
    echo "ok    $label (drill failed as it must: $(grep -o 'FAILED: .*' <<<"$out" | head -1))"
  else
    echo "WRONG $label (exit $rc)"; grep -E 'restore-check|restore-drill|ERROR' <<<"$out" | tail -6 | sed 's/^/      /'
    failures=$((failures + 1))
  fi
}

cleanup
as_role svc_document_migrate SECRET_DB_DOCUMENT_MIGRATE_PASSWORD \
  "CREATE TABLE $PROBE (id int PRIMARY KEY, embedding extensions.vector(3))" >/dev/null
as_role svc_document SECRET_DB_DOCUMENT_PASSWORD \
  "INSERT INTO $PROBE SELECT g, ARRAY[g, g * 2, g * 3]::real[]::extensions.vector FROM generate_series(1, $ROWS) g" >/dev/null

line=$(docker compose run --rm --no-deps -T backup once 2>/dev/null | grep 'backup: ok' | tail -1)
[ -n "$line" ] || { echo "WRONG backup did not succeed"; exit 1; }
name=$(sed -E 's/.*file=([^ ]+).*/\1/' <<<"$line")
base="${name%.dump.gpg}"
echo "ok    backup $name"

in_backups "cd /backups && cp $name verso-selftest-good.dump.gpg && cp $base.manifest verso-selftest-good.manifest \
  && cp $base.acl verso-selftest-good.acl && sha256sum verso-selftest-good.dump.gpg > verso-selftest-good.dump.gpg.sha256"
expect pass "drill restores $ROWS probe rows, privileges and grants" verso-selftest-good.dump.gpg \
  "rows=$ROWS .*app_readable_tables=1"

copy_backup count
in_backups "cd /backups && sed -i -E 's/^(document\.restore_selftest_probe)\|$ROWS$/\1|$((ROWS + 1))/' verso-selftest-count.manifest"
expect fail "changed row count is detected" verso-selftest-count.dump.gpg "FAILED: row counts"

copy_backup acl
in_backups "cd /backups && sed -i -E 's/svc_document=arwd/svc_document=r/' verso-selftest-acl.acl"
expect fail "changed privilege fingerprint is detected" verso-selftest-acl.dump.gpg "FAILED: privileges"

expect fail "restore that loses its grants is detected" verso-selftest-good.dump.gpg "FAILED: privileges" --no-privileges

copy_backup byte
in_backups "cd /backups && printf 'X' | dd of=verso-selftest-byte.dump.gpg bs=1 seek=200 count=1 conv=notrunc status=none"
expect fail "corrupted encrypted file is detected" verso-selftest-byte.dump.gpg "FAILED: checksum mismatch"

copy_backup nosum
in_backups "cd /backups && rm verso-selftest-nosum.dump.gpg.sha256"
expect fail "missing checksum is detected" verso-selftest-nosum.dump.gpg "FAILED: checksum, manifest or acl missing"

if [ $failures -eq 0 ]; then echo "restore-drill-selftest: OK"; else echo "restore-drill-selftest: $failures WRONG"; exit 1; fi
