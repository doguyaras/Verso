#!/usr/bin/env bash
# The bundled identity provider's own database (ADR-0005, ADR-0010): a separate logical database owned by its own
# role, the second step of reference 10.1's separation ladder. Verso's roles cannot connect to it and the keycloak
# role cannot connect to Verso's database.
#
# Runs once on an empty data volume, after 10-roles.sh and 20-database.sh. It is idempotent, so a volume created
# before phase 3 is upgraded by running it by hand (README "Yükseltme"); an existing role keeps its password.
#
# The bundled Keycloak is a demo IdP; in production Verso validates the customer's IdP and this database is unused.
set -euo pipefail
SECRETS="${VERSO_SECRETS_DIR:-/run/secrets}"
if [ ! -r "$SECRETS/SECRET_DB_KEYCLOAK_PASSWORD" ] || [ ! -s "$SECRETS/SECRET_DB_KEYCLOAK_PASSWORD" ]; then
  echo "30-keycloak: secret SECRET_DB_KEYCLOAK_PASSWORD missing, empty or unreadable" >&2
  exit 1
fi

# On a running server pg_stat_statements is loaded: a CREATE ROLE ... PASSWORD would be stored there and in PGDATA
# (reference-feedback R44). During initdb the setting is a harmless placeholder.
psql -v ON_ERROR_STOP=1 -X -q --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOSQL
SET pg_stat_statements.track_utility = off;
SET log_min_error_statement = panic;
\\set keycloak_pw \`cat $SECRETS/SECRET_DB_KEYCLOAK_PASSWORD\`
SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keycloak') AS create_role \\gset
\\if :create_role
CREATE ROLE keycloak LOGIN PASSWORD :'keycloak_pw';
\\endif
ALTER ROLE keycloak SET statement_timeout = '30s';
SELECT NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'keycloak') AS create_db \\gset
\\if :create_db
CREATE DATABASE keycloak OWNER keycloak;
\\endif
REVOKE ALL ON DATABASE keycloak FROM PUBLIC;
GRANT CONNECT ON DATABASE keycloak TO keycloak;
EOSQL

# Fail closed, like 10-roles.sh: the role must have a password.
missing=$(psql -X -q -t -A -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -c \
  "SELECT count(*) FROM pg_authid WHERE rolname = 'keycloak' AND rolpassword IS NULL")
if [ "$missing" != "0" ]; then
  echo "30-keycloak: role keycloak has no password" >&2
  exit 1
fi
echo "30-keycloak: database keycloak ready"
