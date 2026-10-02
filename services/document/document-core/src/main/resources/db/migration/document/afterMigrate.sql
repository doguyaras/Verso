-- Flyway callback, runs after every migrate as the migration role (reference 10.1). The default privileges of the
-- "document" schema grant the application role DML on every table the migration role creates, flyway_schema_history
-- included; a compromised application could then delete history rows and break the next deploy. This takes the
-- table back. Idempotent: it also repairs a grant someone added by hand.
REVOKE ALL ON "${flyway:defaultSchema}"."${flyway:table}" FROM svc_document;
