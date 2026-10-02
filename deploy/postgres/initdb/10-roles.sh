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
MODULES=(document)   # one entry per module schema; a new module adds its schema here and its two secret files

for f in SECRET_DB_BACKUP_PASSWORD; do
  [ -s "$SECRETS/$f" ] || { echo "10-roles: missing secret $f" >&2; exit 1; }
done

sql="SET log_min_error_statement = panic;"
for schema in "${MODULES[@]}"; do
  upper="$(printf '%s' "$schema" | tr '[:lower:]' '[:upper:]')"
  for kind in MIGRATE_PASSWORD PASSWORD; do
    [ -s "$SECRETS/SECRET_DB_${upper}_${kind}" ] || { echo "10-roles: missing secret SECRET_DB_${upper}_${kind}" >&2; exit 1; }
  done
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
echo "10-roles: roles created for: ${MODULES[*]} + verso_backup"
