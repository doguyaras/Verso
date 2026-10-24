-- Flyway callback after a FAILED migrate, same statement as afterMigrate.sql (phase 2 db review D4): the history table
-- exists already, the default privileges gave the application role DML on it, and afterMigrate does not run.
REVOKE ALL ON "${flyway:defaultSchema}"."${flyway:table}" FROM svc_audit;
