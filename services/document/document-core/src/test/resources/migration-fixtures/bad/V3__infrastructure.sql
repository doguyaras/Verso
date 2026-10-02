-- Infrastructure work inside a migration.
CREATE SCHEMA document;
CREATE TABLE IF NOT EXISTS document.t (id UUID PRIMARY KEY);
GRANT SELECT ON document.t TO svc_document;
