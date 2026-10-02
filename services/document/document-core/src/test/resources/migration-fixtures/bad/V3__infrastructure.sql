-- Infrastructure and privilege work inside a migration.
CREATE SCHEMA document;
CREATE TABLE IF NOT EXISTS document.t (id UUID PRIMARY KEY);
GRANT SELECT ON document.t TO svc_document;
REVOKE ALL ON SCHEMA document FROM svc_document;
SET ROLE svc_qa;
ALTER TABLE document.t OWNER TO svc_qa;
ALTER TABLE document.t SET SCHEMA document;
SELECT set_config('search_path', 'document', false);
DO $$ BEGIN EXECUTE 'select 1'; END $$;
