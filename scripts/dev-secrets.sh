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
  SECRET_DB_KEYCLOAK_PASSWORD          # keycloak: the demo IdP's own database (ADR-0010)
  SECRET_KEYCLOAK_ADMIN_PASSWORD       # keycloak: bootstrap admin of the master realm
  SECRET_KEYCLOAK_CI_CLIENT_SECRET     # keycloak: verso-ci client (client credentials, CI smoke test)
  SECRET_KEYCLOAK_DEMO_USER_PASSWORD   # keycloak: the "demo" user of the verso realm
  SECRET_GRAFANA_ADMIN_PASSWORD        # grafana (profile "obs"): the "admin" user (ADR-0014)
)

# Permissions (Linux/macOS hosts; Windows ignores them): the folder is 0700, so no other host user can reach the
# files; the files are 0644, because compose mounts file secrets as bind mounts with the host owner and mode, and the
# containers read them as non-root users (postgres 999, application 10001). A 0600 file owned by the deploying user
# is unreadable for both: the stack does not start (first restore-drill CI run, 2026-10-02).
umask 022
mkdir -p "$DIR"
chmod 700 "$DIR" 2>/dev/null || true
created=0
for name in "${NAMES[@]}"; do
  file="$DIR/$name"
  if [ -s "$file" ]; then continue; fi
  value="$(head -c 96 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 40)"
  [ "${#value}" -eq 40 ] || { echo "dev-secrets: random source failed" >&2; exit 1; }
  printf '%s' "$value" > "$file"
  created=$((created + 1))
done
for name in "${NAMES[@]}"; do chmod 644 "$DIR/$name" 2>/dev/null || true; done
echo "dev-secrets: $created created, $(( ${#NAMES[@]} - created )) kept ($DIR)"
# New secrets do not reach an existing database: the init scripts ran once, with the old values (review E3).
if [ "$created" -gt 0 ] && command -v docker >/dev/null 2>&1    && docker volume inspect "${COMPOSE_PROJECT_NAME:-verso}_pgdata" >/dev/null 2>&1; then
  echo "dev-secrets: WARNING volume ${COMPOSE_PROJECT_NAME:-verso}_pgdata already exists; its roles keep the old passwords." >&2
  echo "dev-secrets: either restore the old secret files, or recreate the database: docker compose down -v" >&2
  echo "dev-secrets: (down -v also deletes the backups volume; copy the backups first)" >&2
fi
