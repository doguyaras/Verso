-- More infrastructure work inside a migration.
CREATE EXTENSION hstore;
DROP SCHEMA scratch;
SET search_path = document, public;
ALTER DEFAULT PRIVILEGES IN SCHEMA document GRANT SELECT ON TABLES TO svc_document;
ALTER ROLE svc_document SET statement_timeout = 0;
SET SESSION AUTHORIZATION svc_qa;
