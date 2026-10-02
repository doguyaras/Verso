#!/usr/bin/env bash
# Server settings, one source for compose, Testcontainers and the restore drill (docs/adr/0009-veri-altyapisi.md).
# Runs once, on an empty data volume (docker-entrypoint-initdb.d). ALTER SYSTEM writes postgresql.auto.conf; the
# entrypoint restarts the server after init, so shared_preload_libraries is active on the first real start.
set -euo pipefail

psql -v ON_ERROR_STOP=1 -X -q --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'EOSQL'
-- Query statistics (reference 10.5); the extension itself is created in 20-database.sh.
ALTER SYSTEM SET shared_preload_libraries = 'pg_stat_statements';
-- Logs never carry bind parameters: they may hold document text or questions (llm-rules 2.1, reference 8.4).
ALTER SYSTEM SET log_parameter_max_length = 0;
ALTER SYSTEM SET log_parameter_max_length_on_error = 0;
ALTER SYSTEM SET log_statement = 'none';
-- A failing constraint logs DETAIL with the whole row ("Failing row contains (...)", "Key (title)=(...)"): document
-- text and file names. terse keeps the error and the statement with $n placeholders only (phase 2 security S1).
ALTER SYSTEM SET log_error_verbosity = 'terse';
ALTER SYSTEM SET password_encryption = 'scram-sha-256';
EOSQL
