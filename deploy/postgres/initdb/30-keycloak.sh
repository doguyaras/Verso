#!/usr/bin/env bash
# The bundled identity provider's own database (ADR-0005, ADR-0010): a separate logical database owned by its own
# role, the second step of reference 10.1's separation ladder. Verso's roles cannot connect to it and the keycloak
# role cannot connect to Verso's database. Runs once, on an empty data volume, after 10-roles.sh and 20-database.sh.
#
# The bundled Keycloak is a demo IdP; in production Verso validates the customer's IdP and this database is unused.
set -euo pipefail
SECRETS="${VERSO_SECRETS_DIR:-/run/secrets}"
if [ ! -r "$SECRETS/SECRET_DB_KEYCLOAK_PASSWORD" ] || [ ! -s "$SECRETS/SECRET_DB_KEYCLOAK_PASSWORD" ]; then
  echo "30-keycloak: secret SECRET_DB_KEYCLOAK_PASSWORD missing, empty or unreadable" >&2
  exit 1
fi

psql -v ON_ERROR_STOP=1 -X -q --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOSQL
SET log_min_error_statement = panic;
\\set keycloak_pw \`cat $SECRETS/SECRET_DB_KEYCLOAK_PASSWORD\`
CREATE ROLE keycloak LOGIN PASSWORD :'keycloak_pw';
ALTER ROLE keycloak SET statement_timeout = '30s';
CREATE DATABASE keycloak OWNER keycloak;
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
echo "30-keycloak: database keycloak created"
