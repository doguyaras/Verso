-- Turkish personal data pseudonymisation of external model calls (ADR-0059). Owner: the qa module, schema "qa".
--
-- pii_masking: the gateway's masking switch, a verso-admin's setting next to user_model_choice. On by default: an
-- installation that never touches it masks every cloud call. Local calls are never masked (nothing leaves).
--
-- masked_counts: per call, how many distinct values of each type the filter replaced, e.g. {"TCKN": 1, "IBAN": 2}.
-- Type names are fixed words; the values themselves and the placeholder mapping are never stored anywhere (they live
-- only in memory for the duration of one call).
--
-- Both columns have constant defaults (PostgreSQL 11+: no table rewrite, a short ACCESS EXCLUSIVE lock only). The CHECK
-- on qa.model_usage is NOT VALID: it holds for every new row, and every existing row reads the default '{}', so no scan
-- of the usage table (13 months of calls) is needed.
--
-- Rollout: additive; the old image neither reads nor writes the new columns (its inserts get the default). Rollback:
-- ALTER TABLE qa.model_usage DROP CONSTRAINT ck_model_usage_masked_counts, DROP COLUMN masked_counts;
-- ALTER TABLE qa.model_gateway_setting DROP COLUMN pii_masking.

ALTER TABLE qa.model_gateway_setting ADD COLUMN pii_masking boolean NOT NULL DEFAULT true;

ALTER TABLE qa.model_usage ADD COLUMN masked_counts jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE qa.model_usage ADD CONSTRAINT ck_model_usage_masked_counts
    CHECK (jsonb_typeof(masked_counts) = 'object') NOT VALID;
