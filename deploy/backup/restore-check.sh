#!/usr/bin/env bash
# Restore drill, inside the compose "restore-runner" service (profile "drill"; run by scripts/restore-drill.sh).
# Target: restore-db, a throwaway PostgreSQL that has the roles only (10-roles.sh); schemas, extensions, grants and
# data must all come from the backup. "An untested backup is not a backup" (reference 10.5).
#
# Steps: pick the backup (BACKUP_FILE or the newest) -> verify SHA-256 -> decrypt -> pg_restore --exit-on-error ->
# compare exact row counts with the manifest taken in the backup's snapshot -> the application role connects, reads
# and still cannot run DDL. Flyway validate runs afterwards in its own container (restore-flyway).
# Output: file names, counts and timings only.
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/backups}"
SECRETS="${VERSO_SECRETS_DIR:-/run/secrets}"
export GNUPGHOME=/tmp/gnupg
mkdir -p -m 700 "$GNUPGHOME"
started=$(date +%s)
log() { printf '%s restore-check: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
fail() { log "FAILED: $*"; exit 1; }

if [ -n "${BACKUP_FILE:-}" ]; then
  file="$BACKUP_DIR/$(basename "$BACKUP_FILE")"
else
  file=$(ls -1 "$BACKUP_DIR"/verso-*.dump.gpg 2>/dev/null | sort | tail -n 1 || true)
fi
[ -n "$file" ] && [ -s "$file" ] || fail "no backup found in $BACKUP_DIR"
manifest="${file%.dump.gpg}.manifest"
[ -s "$file.sha256" ] && [ -f "$manifest" ] || fail "checksum or manifest missing for $(basename "$file")"
log "backup file=$(basename "$file") bytes=$(stat -c %s "$file")"

(cd "$BACKUP_DIR" && sha256sum --check --quiet "$(basename "$file").sha256") || fail "checksum mismatch"

gpg --batch --quiet --pinentry-mode loopback --passphrase-file "$SECRETS/SECRET_BACKUP_ENCRYPTION_KEY" \
    --decrypt --output /tmp/restore.dump "$file" || fail "decryption"

export PGUSER=postgres
PGPASSWORD="$(cat "$SECRETS/SECRET_POSTGRES_SUPERUSER_PASSWORD")"
export PGPASSWORD
pg_restore --exit-on-error --no-password --dbname="$PGDATABASE" /tmp/restore.dump || fail "pg_restore"
rm -f /tmp/restore.dump

# Same query as backup.sh; the restored database must hold exactly the rows of the backup's snapshot.
psql -X -q -t -A -v ON_ERROR_STOP=1 -c "SELECT n.nspname || '.' || c.relname,
       (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', n.nspname, c.relname),
                                            false, true, '')))[1]::text
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind IN ('r', 'p')
   AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast', 'extensions')
   AND n.nspname NOT LIKE 'pg_temp%'
 ORDER BY 1;" > /tmp/restored.manifest
if ! diff -q "$manifest" /tmp/restored.manifest >/dev/null; then
  log "row counts differ (table|backup vs restored):"
  diff "$manifest" /tmp/restored.manifest | grep '^[<>]' | sed 's/^/  /' || true
  fail "row counts"
fi
tables=$(wc -l < "$manifest")
rows=$(awk -F'|' '{s += $2} END {print s + 0}' "$manifest")

# Grants came back with the dump: the application role reads its schema and still cannot change it.
PGUSER=svc_document PGPASSWORD="$(cat "$SECRETS/SECRET_DB_DOCUMENT_PASSWORD")" \
  psql -X -q -t -A -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM pg_tables WHERE schemaname = 'document';" >/dev/null \
  || fail "application role cannot read its schema"
if PGUSER=svc_document PGPASSWORD="$(cat "$SECRETS/SECRET_DB_DOCUMENT_PASSWORD")" \
   psql -X -q -t -A -v ON_ERROR_STOP=1 -c "CREATE TABLE document.restore_drill_probe (id int);" >/dev/null 2>&1; then
  fail "application role could run DDL after restore"
fi

log "ok file=$(basename "$file") tables=$tables rows=$rows seconds=$(( $(date +%s) - started ))"
