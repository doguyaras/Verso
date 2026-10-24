#!/usr/bin/env bash
# Upgrade path for module schemas (ADR-0023): a data volume created before a module existed has neither its two roles
# nor its schema, because 10-roles.sh and 20-database.sh ran only once, on the empty volume. This script adds what is
# missing and changes nothing that exists: an existing role keeps its password, an existing schema its objects.
#
# On a fresh volume it runs after 10-roles.sh and 20-database.sh and finds everything in place. On an existing volume
# it is run by hand (README "Yükseltme"), like 30-keycloak.sh:
#   docker compose exec -T -u postgres postgres bash /docker-entrypoint-initdb.d/40-modules.sh
#
# Same statements and the same guarantees as 10-roles.sh / 20-database.sh (reference 10.1): passwords read from
# /run/secrets with psql's \set (never in an argument list), the server log never gets a statement of this session.
set -euo pipefail
SECRETS="${VERSO_SECRETS_DIR:-/run/secrets}"
# Must match 10-roles.sh and 20-database.sh (ModuleConsistencyTest).
MODULES=(document qa audit connector)
# Test hook only (DatabaseRolesTest proves the "missing module" path with a throwaway module name).
if [ -n "${VERSO_INIT_MODULES:-}" ]; then read -ra MODULES <<< "$VERSO_INIT_MODULES"; fi

require_secret() {
  if [ ! -r "$SECRETS/$1" ] || [ ! -s "$SECRETS/$1" ]; then
    echo "40-modules: secret $1 missing, empty or unreadable" >&2
    exit 1
  fi
}

# On a running server pg_stat_statements is loaded: a CREATE ROLE ... PASSWORD would be stored there (R44).
sql="SET pg_stat_statements.track_utility = off;
SET log_min_error_statement = panic;"
for schema in "${MODULES[@]}"; do
  case "$schema" in *[!a-z]*|"") echo "40-modules: invalid module name" >&2; exit 1 ;; esac
  upper="$(printf '%s' "$schema" | tr '[:lower:]' '[:upper:]')"
  require_secret "SECRET_DB_${upper}_MIGRATE_PASSWORD"
  require_secret "SECRET_DB_${upper}_PASSWORD"
  sql+="
\\set migrate_pw \`cat $SECRETS/SECRET_DB_${upper}_MIGRATE_PASSWORD\`
\\set app_pw \`cat $SECRETS/SECRET_DB_${upper}_PASSWORD\`
SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'svc_${schema}_migrate') AS create_migrate \\gset
\\if :create_migrate
CREATE ROLE svc_${schema}_migrate LOGIN PASSWORD :'migrate_pw';
\\endif
SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'svc_${schema}') AS create_app \\gset
\\if :create_app
CREATE ROLE svc_${schema} LOGIN PASSWORD :'app_pw';
\\endif
ALTER ROLE svc_${schema} SET statement_timeout = '10s';
ALTER ROLE svc_${schema} SET lock_timeout = '3s';
ALTER ROLE svc_${schema} SET idle_in_transaction_session_timeout = '60s';
ALTER ROLE svc_${schema}_migrate SET lock_timeout = '10s';
ALTER ROLE svc_${schema} SET search_path = ${schema}, extensions;
ALTER ROLE svc_${schema}_migrate SET search_path = ${schema}, extensions;
GRANT CONNECT ON DATABASE \"$POSTGRES_DB\" TO svc_${schema}, svc_${schema}_migrate;
GRANT USAGE ON SCHEMA extensions TO svc_${schema}, svc_${schema}_migrate;
SELECT NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = '${schema}') AS create_schema \\gset
\\if :create_schema
CREATE SCHEMA ${schema} AUTHORIZATION svc_${schema}_migrate;
\\endif
GRANT USAGE ON SCHEMA ${schema} TO svc_${schema};
ALTER DEFAULT PRIVILEGES FOR ROLE svc_${schema}_migrate IN SCHEMA ${schema}
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO svc_${schema};
ALTER DEFAULT PRIVILEGES FOR ROLE svc_${schema}_migrate IN SCHEMA ${schema}
    GRANT USAGE, SELECT ON SEQUENCES TO svc_${schema};
"
done

printf '%s\n' "$sql" | psql -v ON_ERROR_STOP=1 -X -q --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"

# Fail closed, like 10-roles.sh: every module login role must have a password.
missing=$(psql -X -q -t -A -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -c \
  "SELECT coalesce(string_agg(rolname, ','), '') FROM pg_authid WHERE rolcanlogin AND rolpassword IS NULL
    AND rolname LIKE 'svc%'")
if [ -n "$missing" ]; then
  echo "40-modules: roles without password: $missing" >&2
  exit 1
fi
echo "40-modules: module roles and schemas present for: ${MODULES[*]}"
