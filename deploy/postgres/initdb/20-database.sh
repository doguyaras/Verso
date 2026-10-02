#!/usr/bin/env bash
# Schemas, extensions and grants (reference 10.1, ADR-0002). Runs once, on an empty data volume, after 10-roles.sh.
# Ownership is enforced by GRANT, not by tests: the application role can read and write the tables of its own schema
# and nothing else; DDL belongs to the migration role alone.
set -euo pipefail
MODULES=(document)

sql="
-- Nothing is reachable by default: no public schema (database access: 10-roles.sh).
REVOKE ALL ON SCHEMA public FROM PUBLIC;

-- Extensions live in their own schema, owned by the superuser: modules use them, cannot alter them.
CREATE SCHEMA extensions;
CREATE EXTENSION vector SCHEMA extensions;
CREATE EXTENSION pg_stat_statements SCHEMA extensions;
"
for schema in "${MODULES[@]}"; do
  sql+="
GRANT USAGE ON SCHEMA extensions TO svc_${schema}, svc_${schema}_migrate;
-- The migration role owns the schema (DDL); V1 starts with the first table, never with CREATE SCHEMA (reference 10.2).
CREATE SCHEMA ${schema} AUTHORIZATION svc_${schema}_migrate;
GRANT USAGE ON SCHEMA ${schema} TO svc_${schema};
-- Objects the migration role creates later are usable by the application role without a GRANT per migration.
-- flyway_schema_history gets these rights too; db/migration/${schema}/afterMigrate.sql revokes them after every
-- migrate (reference 10.1: a compromised application must not be able to rewrite the history).
ALTER DEFAULT PRIVILEGES FOR ROLE svc_${schema}_migrate IN SCHEMA ${schema}
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO svc_${schema};
ALTER DEFAULT PRIVILEGES FOR ROLE svc_${schema}_migrate IN SCHEMA ${schema}
    GRANT USAGE, SELECT ON SEQUENCES TO svc_${schema};
"
done

printf '%s\n' "$sql" | psql -v ON_ERROR_STOP=1 -X -q --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"
echo "20-database: schemas created for: ${MODULES[*]}"
