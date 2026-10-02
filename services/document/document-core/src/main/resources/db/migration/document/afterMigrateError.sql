-- Flyway callback after a FAILED migrate, same statement as afterMigrate.sql (phase 2 db review D4): when the first
-- migrate fails, Flyway has already created flyway_schema_history, the default privileges have given the application
-- role DML on it, and afterMigrate does not run. Without this, the application role could insert a forged
-- "success" row before the next deploy.
REVOKE ALL ON "${flyway:defaultSchema}"."${flyway:table}" FROM svc_document;
