#!/usr/bin/env bash
# Self-test of backup + restore drill against the running compose stack (CI: .github/workflows/restore-drill.yml).
# A drill that never fails proves nothing: this script shows it passes on real data and fails on every tampering.
#
#   docker compose up -d --build --wait && bash scripts/restore-drill-selftest.sh
#
# 1. Data: a probe table with a vector column (migration role) and rows written by the application role.
# 2. Backup, then the drill must pass and report exactly those rows.
# 3. Tampered copies must each fail: changed row count in the manifest, flipped byte in the encrypted dump, missing
#    checksum file.
# The probe table and every self-test file are removed at the end, pass or fail.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
ROWS=250
PROBE=document.restore_selftest_probe
failures=0

as_role() { # as_role <role> <secret> <sql>
  docker compose exec -T postgres bash -c \
    "PGPASSWORD=\"\$(cat /run/secrets/$2)\" psql -h 127.0.0.1 -U $1 -d verso -X -q -t -A -v ON_ERROR_STOP=1 -c \"$3\""
}
in_backups() { docker compose run --rm --no-deps -T --entrypoint bash backup -c "$1"; }

cleanup() {
  as_role svc_document_migrate SECRET_DB_DOCUMENT_MIGRATE_PASSWORD "DROP TABLE IF EXISTS $PROBE" >/dev/null 2>&1 || true
  in_backups 'rm -f /backups/verso-selftest-* /backups/verso-zz-selftest-*' >/dev/null 2>&1 || true
}
trap cleanup EXIT

expect() { # expect pass|fail <label> <BACKUP_FILE> [pattern]
  local want=$1 label=$2 file=$3 pattern=${4:-} out rc=0
  out=$(BACKUP_FILE="$file" bash scripts/restore-drill.sh 2>&1) || rc=$?
  if [ "$want" = pass ] && [ $rc -eq 0 ] && { [ -z "$pattern" ] || grep -q "$pattern" <<<"$out"; }; then
    echo "ok    $label"
  elif [ "$want" = fail ] && [ $rc -ne 0 ] && { [ -z "$pattern" ] || grep -q "$pattern" <<<"$out"; }; then
    echo "ok    $label (drill failed as it must: $(grep -o 'FAILED: [^$]*' <<<"$out" | head -1))"
  else
    echo "WRONG $label (exit $rc)"; grep -E 'restore-check|restore-drill|ERROR' <<<"$out" | tail -5 | sed 's/^/      /'
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

# The newest backup holds the probe rows: the drill restores them exactly.
in_backups "cd /backups && cp $name verso-selftest-good.dump.gpg && cp $base.manifest verso-selftest-good.manifest \
  && sha256sum verso-selftest-good.dump.gpg > verso-selftest-good.dump.gpg.sha256"
expect pass "drill restores $ROWS probe rows" verso-selftest-good.dump.gpg "rows=$ROWS"

# Manifest says one row more than the snapshot had.
in_backups "cd /backups && cp verso-selftest-good.dump.gpg verso-selftest-count.dump.gpg \
  && sed -E 's/^(document\.restore_selftest_probe)\|$ROWS$/\1|$((ROWS + 1))/' verso-selftest-good.manifest > verso-selftest-count.manifest \
  && sha256sum verso-selftest-count.dump.gpg > verso-selftest-count.dump.gpg.sha256"
expect fail "changed row count is detected" verso-selftest-count.dump.gpg "row counts"

# One byte of the encrypted file flipped after the checksum was written.
in_backups "cd /backups && cp verso-selftest-good.dump.gpg verso-selftest-byte.dump.gpg && cp verso-selftest-good.manifest verso-selftest-byte.manifest \
  && sha256sum verso-selftest-byte.dump.gpg > verso-selftest-byte.dump.gpg.sha256 \
  && printf 'X' | dd of=verso-selftest-byte.dump.gpg bs=1 seek=200 count=1 conv=notrunc status=none"
expect fail "corrupted encrypted file is detected" verso-selftest-byte.dump.gpg "checksum"

# Checksum file missing.
in_backups "cd /backups && cp verso-selftest-good.dump.gpg verso-selftest-nosum.dump.gpg && cp verso-selftest-good.manifest verso-selftest-nosum.manifest"
expect fail "missing checksum is detected" verso-selftest-nosum.dump.gpg "checksum or manifest missing"

if [ $failures -eq 0 ]; then echo "restore-drill-selftest: OK"; else echo "restore-drill-selftest: $failures WRONG"; exit 1; fi
