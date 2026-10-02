#!/usr/bin/env bash
# Creates the secret files that compose mounts under /run/secrets (reference 15.3, level 1). Existing files are kept,
# so the script is safe to re-run; it never prints a secret. Values are random, 40 alphanumeric characters.
#
#   bash scripts/dev-secrets.sh            # repository root; writes secrets/<NAME>
#
# The file name is the property name: Spring's config tree turns /run/secrets/SECRET_DB_DOCUMENT_PASSWORD into the
# property SECRET_DB_DOCUMENT_PASSWORD, which config/verso.yml reads without a fallback. In production the deploy job
# writes the same files from CI secrets instead of running this script (docs/adr/0009-veri-altyapisi.md).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="$ROOT/secrets"
NAMES=(
  SECRET_POSTGRES_SUPERUSER_PASSWORD   # postgres container only (init scripts, restore drill)
  SECRET_DB_DOCUMENT_MIGRATE_PASSWORD  # svc_document_migrate: schema owner, Flyway
  SECRET_DB_DOCUMENT_PASSWORD          # svc_document: application, DML only
  SECRET_DB_BACKUP_PASSWORD            # verso_backup: pg_read_all_data, pg_dump only
  SECRET_BACKUP_ENCRYPTION_KEY         # gpg passphrase for backup files
)

umask 077
mkdir -p "$DIR"
created=0
for name in "${NAMES[@]}"; do
  file="$DIR/$name"
  if [ -s "$file" ]; then continue; fi
  value="$(head -c 96 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 40)"
  [ "${#value}" -eq 40 ] || { echo "dev-secrets: random source failed" >&2; exit 1; }
  printf '%s' "$value" > "$file"
  chmod 600 "$file" 2>/dev/null || true
  created=$((created + 1))
done
echo "dev-secrets: $created created, $(( ${#NAMES[@]} - created )) kept ($DIR)"
