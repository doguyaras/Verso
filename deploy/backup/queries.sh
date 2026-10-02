#!/usr/bin/env bash
# Queries shared by backup.sh (source, inside the dump's snapshot) and restore-check.sh (restored copy): both sides
# must produce byte-identical output when the restore is faithful. Sourced, never run.

# Exact row count of every user table (catalog and extension schemas excluded): "schema.table|rows".
MANIFEST_SQL="SELECT n.nspname || '.' || c.relname,
       (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', n.nspname, c.relname),
                                            false, true, '')))[1]::text
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind IN ('r', 'p')
   AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast', 'extensions')
   AND n.nspname NOT LIKE 'pg_temp%'
 ORDER BY 1;"

# Privilege fingerprint (review D1): owners and ACLs of the database, every non-catalog schema and relation, and the
# default privileges. A NULL ACL is normalised to acldefault() and items are sorted, so an explicit owner-only ACL and
# the implicit default compare equal. Catalog tables are readable by every role, the backup role included.
ACL_SQL="WITH norm AS (
  SELECT 'database' AS kind, d.datname AS name, d.datdba::regrole::text AS owner,
         coalesce(d.datacl, acldefault('d', d.datdba)) AS acl
    FROM pg_database d WHERE d.datname = current_database()
  UNION ALL
  SELECT 'schema', n.nspname, n.nspowner::regrole::text, coalesce(n.nspacl, acldefault('n', n.nspowner))
    FROM pg_namespace n
   WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast') AND n.nspname NOT LIKE 'pg_temp%'
     AND n.nspname NOT LIKE 'pg_toast_temp%'
  UNION ALL
  SELECT 'relation:' || c.relkind::text, n.nspname || '.' || c.relname, c.relowner::regrole::text,
         coalesce(c.relacl, acldefault(CASE WHEN c.relkind = 'S' THEN 's' ELSE 'r' END::\"char\", c.relowner))
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
     AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast') AND n.nspname NOT LIKE 'pg_temp%'
  UNION ALL
  SELECT 'default:' || a.defaclobjtype::text, coalesce(a.defaclnamespace::regnamespace::text, '*'),
         a.defaclrole::regrole::text, a.defaclacl
    FROM pg_default_acl a
)
SELECT kind || '|' || name || '|' || owner || '|' ||
       coalesce((SELECT string_agg(x::text, ',' ORDER BY x::text) FROM unnest(acl) x), '')
  FROM norm ORDER BY 1;"
