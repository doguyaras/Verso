#!/usr/bin/env bash
# Restore drill, inside the compose "restore-runner" service (profile "drill"; run by scripts/restore-drill.sh).
# Target: restore-db, a throwaway PostgreSQL that has the roles only (10-roles.sh); schemas, extensions, grants and
# data must all come from the backup. "An untested backup is not a backup" (reference 10.5).
#
# Steps: pick the backup (BACKUP_FILE or the newest) -> verify SHA-256 -> decrypt -> pg_restore --exit-on-error ->
# compare exact row counts and the privilege fingerprint with the files taken in the backup's snapshot -> the
# application role reads every table of its schema except the Flyway history, cannot read the history and cannot run
# DDL. Flyway validate runs afterwards in its own container (restore-flyway).
# Output: file names, counts and timings only.
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/backups}"
SECRETS="${VERSO_SECRETS_DIR:-/run/secrets}"
export GNUPGHOME=/tmp/gnupg
mkdir -p -m 700 "$GNUPGHOME"
started=$(date +%s)
log() { printf '%s restore-check: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
fail() { log "FAILED: $*"; exit 1; }

# One entry per module schema; must match deploy/postgres/initdb/10-roles.sh (ModuleConsistencyTest).
MODULES=(document qa audit connector)

for f in SECRET_POSTGRES_SUPERUSER_PASSWORD SECRET_BACKUP_ENCRYPTION_KEY; do
  [ -r "$SECRETS/$f" ] && [ -s "$SECRETS/$f" ] || fail "secret $f missing or unreadable"
done
for schema in "${MODULES[@]}"; do
  f="SECRET_DB_$(printf '%s' "$schema" | tr '[:lower:]' '[:upper:]')_PASSWORD"
  [ -r "$SECRETS/$f" ] && [ -s "$SECRETS/$f" ] || fail "secret $f missing or unreadable"
done

if [ -n "${BACKUP_FILE:-}" ]; then
  file="$BACKUP_DIR/$(basename "$BACKUP_FILE")"
else
  file=$(ls -1 "$BACKUP_DIR"/verso-*.dump.gpg 2>/dev/null | sort | tail -n 1 || true)
fi
[ -n "$file" ] && [ -s "$file" ] || fail "no backup found in $BACKUP_DIR"
manifest="${file%.dump.gpg}.manifest"
acl="${file%.dump.gpg}.acl"
[ -s "$file.sha256" ] && [ -f "$manifest" ] && [ -s "$acl" ] || fail "checksum, manifest or acl missing for $(basename "$file")"
log "backup file=$(basename "$file") bytes=$(stat -c %s "$file")"

# MANIFEST_SQL and ACL_SQL: the same definitions backup.sh used.
# shellcheck source=queries.sh
. "$(dirname "${BASH_SOURCE[0]}")/queries.sh"

(cd "$BACKUP_DIR" && sha256sum --check --quiet "$(basename "$file").sha256") || fail "checksum mismatch"

gpg --batch --quiet --pinentry-mode loopback --passphrase-file "$SECRETS/SECRET_BACKUP_ENCRYPTION_KEY" \
    --decrypt --output /tmp/restore.dump "$file" || fail "decryption"

export PGUSER=postgres
PGPASSWORD="$(cat "$SECRETS/SECRET_POSTGRES_SUPERUSER_PASSWORD")"
export PGPASSWORD
# Test hook for scripts/restore-drill-selftest.sh only: a restore that really loses its grants must turn the drill
# red. Anything but the one allowed value is refused.
case "${RESTORE_OPTIONS:-}" in
  "") restore_options=() ;;
  --no-privileges) restore_options=(--no-privileges); log "test hook: restoring without privileges" ;;
  *) fail "RESTORE_OPTIONS accepts only --no-privileges" ;;
esac
pg_restore --exit-on-error --no-password "${restore_options[@]}" --dbname="$PGDATABASE" /tmp/restore.dump \
  || fail "pg_restore"
rm -f /tmp/restore.dump

# The restored database must hold exactly the rows and the privileges of the backup's snapshot.
psql -X -q -t -A -v ON_ERROR_STOP=1 -c "$MANIFEST_SQL" > /tmp/restored.manifest
if ! diff -q "$manifest" /tmp/restored.manifest >/dev/null; then
  log "row counts differ (table|backup vs restored):"
  diff "$manifest" /tmp/restored.manifest | grep '^[<>]' | sed 's/^/  /' || true
  fail "row counts"
fi
psql -X -q -t -A -v ON_ERROR_STOP=1 -c "$ACL_SQL" > /tmp/restored.acl
if ! diff -q "$acl" /tmp/restored.acl >/dev/null; then
  log "privileges differ (backup vs restored):"
  diff "$acl" /tmp/restored.acl | grep '^[<>]' | sed 's/^/  /' || true
  fail "privileges"
fi
tables=$(wc -l < "$manifest")
rows=$(awk -F'|' '{s += $2} END {print s + 0}' "$manifest")

# Grants checked by use as well (a catalog query passes without any privilege; review D1): each module's application
# role reads every table of its own schema, cannot read its Flyway history and cannot run DDL (ADR-0023: per schema).
as_app() {
  local schema="$1" upper
  upper="$(printf '%s' "$schema" | tr '[:lower:]' '[:upper:]')"
  PGUSER="svc_$schema" PGPASSWORD="$(cat "$SECRETS/SECRET_DB_${upper}_PASSWORD")" \
    psql -X -q -t -A -v ON_ERROR_STOP=1 -c "$2"
}
readable=0
for schema in "${MODULES[@]}"; do
  while IFS='|' read -r table _; do
    case "$table" in
      "$schema".flyway_schema_history) continue ;;
      "$schema".*)
        as_app "$schema" "SELECT 1 FROM $table LIMIT 1;" >/dev/null 2>&1 \
          || fail "application role of $schema cannot read $table"
        readable=$((readable + 1))
        ;;
    esac
  done < "$manifest"
  if as_app "$schema" "SELECT 1 FROM $schema.flyway_schema_history LIMIT 1;" >/dev/null 2>&1; then
    fail "application role of $schema can read the Flyway history after restore"
  fi
  if as_app "$schema" "CREATE TABLE $schema.restore_drill_probe (id int);" >/dev/null 2>&1; then
    fail "application role of $schema could run DDL after restore"
  fi
done

log "ok file=$(basename "$file") tables=$tables rows=$rows acl_entries=$(wc -l < "$acl") app_readable_tables=$readable seconds=$(( $(date +%s) - started ))"
