#!/usr/bin/env bash
# Login roles (reference 10.1). Per module: <schema> migration role (schema owner, DDL, Flyway only) and application
# role (DML only). Plus verso_backup (pg_read_all_data) for pg_dump. Runs once, on an empty data volume.
#
# The restore drill runs this script alone on its target: roles are cluster-wide and not part of pg_dump; schema,
# extension and grants come from the dump itself.
#
# Passwords are read from /run/secrets with psql's \set and backticks: they never appear in a process argument list.
# log_min_error_statement = panic for this session: a failing CREATE ROLE would otherwise be logged with its password.
set -euo pipefail
SECRETS="${VERSO_SECRETS_DIR:-/run/secrets}"
# One entry per module schema; must match 20-database.sh (ModuleConsistencyTest). A new module adds its schema here,
# its two secret files and its migration folder.
MODULES=(document)

# Readable and non-empty, checked before use: psql's backtick turns an unreadable file into an empty string, and
# PostgreSQL answers an empty password with a NOTICE and a role WITHOUT password while init reports success (phase 2
# reviews C1/S2: 0600 secret files owned by the deploying user on Linux).
require_secret() {
  if [ ! -r "$SECRETS/$1" ] || [ ! -s "$SECRETS/$1" ]; then
    echo "10-roles: secret $1 missing, empty or unreadable" >&2
    exit 1
  fi
}
require_secret SECRET_DB_BACKUP_PASSWORD

sql="SET log_min_error_statement = panic;"
for schema in "${MODULES[@]}"; do
  upper="$(printf '%s' "$schema" | tr '[:lower:]' '[:upper:]')"
  require_secret "SECRET_DB_${upper}_MIGRATE_PASSWORD"
  require_secret "SECRET_DB_${upper}_PASSWORD"
  sql+="
\\set migrate_pw \`cat $SECRETS/SECRET_DB_${upper}_MIGRATE_PASSWORD\`
\\set app_pw \`cat $SECRETS/SECRET_DB_${upper}_PASSWORD\`
CREATE ROLE svc_${schema}_migrate LOGIN PASSWORD :'migrate_pw';
CREATE ROLE svc_${schema} LOGIN PASSWORD :'app_pw';
-- Role-bound timeouts (reference 10.1): runaway query, lock queue, forgotten transaction. The migration role only
-- gets a lock timeout: DDL must not queue in front of production traffic; backfills may run long.
ALTER ROLE svc_${schema} SET statement_timeout = '10s';
ALTER ROLE svc_${schema} SET lock_timeout = '3s';
ALTER ROLE svc_${schema} SET idle_in_transaction_session_timeout = '60s';
ALTER ROLE svc_${schema}_migrate SET lock_timeout = '10s';
-- Own schema first, then shared extensions (vector type and operators); never public.
ALTER ROLE svc_${schema} SET search_path = ${schema}, extensions;
ALTER ROLE svc_${schema}_migrate SET search_path = ${schema}, extensions;
"
done
sql+="
\\set backup_pw \`cat $SECRETS/SECRET_DB_BACKUP_PASSWORD\`
-- Read-only, for pg_dump: pg_read_all_data (PostgreSQL 14+) instead of a superuser.
CREATE ROLE verso_backup LOGIN PASSWORD :'backup_pw' IN ROLE pg_read_all_data;
ALTER ROLE verso_backup SET statement_timeout = 0;
-- Database-level access lives here, not in 20-database.sh: pg_dump does not carry database ACLs, and the restore
-- drill runs this script alone on its target. Nobody connects unless named.
REVOKE ALL ON DATABASE \"$POSTGRES_DB\" FROM PUBLIC;
GRANT CONNECT ON DATABASE \"$POSTGRES_DB\" TO verso_backup;
"
for schema in "${MODULES[@]}"; do
  sql+="GRANT CONNECT ON DATABASE \"$POSTGRES_DB\" TO svc_${schema}, svc_${schema}_migrate;
"
done

printf '%s\n' "$sql" | psql -v ON_ERROR_STOP=1 -X -q --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"

# Fail closed: every login role created here must have a password.
missing=$(psql -X -q -t -A -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -c \
  "SELECT coalesce(string_agg(rolname, ','), '') FROM pg_authid
    WHERE rolcanlogin AND rolpassword IS NULL AND (rolname LIKE 'svc%' OR rolname = 'verso_backup')")
if [ -n "$missing" ]; then
  echo "10-roles: roles without password: $missing" >&2
  exit 1
fi
echo "10-roles: roles created for: ${MODULES[*]} + verso_backup"
