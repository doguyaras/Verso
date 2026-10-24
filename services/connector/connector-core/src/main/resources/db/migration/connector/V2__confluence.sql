-- Confluence connector (ADR-0044) on the same tables as SMB (ADR-0038): a connector row gets a type and the Confluence
-- settings; the SMB-only columns become optional for it. Additive: existing SMB rows keep every value and every check.
--
-- Still no secret is stored: credential_ref names a secret file (the personal access token or the API token), and the
-- Cloud account e-mail is the "username". A page's title is not stored here either: connector_item keeps "pages/<id>"
-- and "attachments/<page id>/<attachment id>" as paths, never a title, so nothing human-readable of a page reaches
-- this schema.

-- contract: the DROPs only widen checks (a new type, SMB-only NOT NULLs moved into a type-scoped check, "+" in
-- account names); every row the previous release writes still satisfies them, so it runs on this schema.
ALTER TABLE connector.connector DROP CONSTRAINT ck_connector_type;
ALTER TABLE connector.connector ADD CONSTRAINT ck_connector_type CHECK (type IN ('SMB', 'CONFLUENCE'));

ALTER TABLE connector.connector ALTER COLUMN share DROP NOT NULL;
ALTER TABLE connector.connector ALTER COLUMN username DROP NOT NULL;
ALTER TABLE connector.connector ADD CONSTRAINT ck_connector_smb_fields
    CHECK (type <> 'SMB' OR (share IS NOT NULL AND username IS NOT NULL));

-- A Cloud account is an e-mail address, which may hold "+"; an SMB account name still may not (the API checks per type).
ALTER TABLE connector.connector DROP CONSTRAINT ck_connector_username;
ALTER TABLE connector.connector ADD CONSTRAINT ck_connector_username
    CHECK (username IS NULL OR username ~ '^[A-Za-z0-9._@$+ -]{1,104}$');

ALTER TABLE connector.connector
    -- https://host[:port][/context], no user info, query or fragment; host and port are also in host/port.
    ADD COLUMN base_url            text,
    ADD COLUMN deployment          text,
    ADD COLUMN space_keys          text[]      NOT NULL DEFAULT '{}',
    ADD COLUMN label               text,
    ADD COLUMN ancestor_id         bigint,
    ADD COLUMN include_attachments boolean     NOT NULL DEFAULT false,
    -- The v1 safety rule (ADR-0044): a page with any read restriction (its own or an ancestor's) is not synced.
    ADD COLUMN skip_restricted     boolean     NOT NULL DEFAULT true,
    ADD COLUMN page_size           integer     NOT NULL DEFAULT 50,
    -- Incremental sync: the newest "last modified" a complete listing has fully applied; NULL: list everything.
    ADD COLUMN sync_cursor         timestamptz,
    -- The last complete full listing (deletions, archived pages and the restriction sweep); NULL: never.
    ADD COLUMN full_listing_at     timestamptz,
    ADD CONSTRAINT ck_connector_confluence_fields
        CHECK (type <> 'CONFLUENCE' OR (base_url IS NOT NULL AND deployment IS NOT NULL AND cardinality(space_keys) >= 1)),
    ADD CONSTRAINT ck_connector_base_url
        CHECK (base_url IS NULL OR (base_url ~ '^https?://[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?(/[A-Za-z0-9._~-]+)*$'
            AND char_length(base_url) <= 2048)),
    ADD CONSTRAINT ck_connector_deployment CHECK (deployment IS NULL OR deployment IN ('DATA_CENTER', 'CLOUD')),
    ADD CONSTRAINT ck_connector_space_keys CHECK (cardinality(space_keys) <= 20
        AND array_to_string(space_keys, ',') ~ '^([A-Za-z0-9_~.-]{1,100}(,|$))*$'),
    ADD CONSTRAINT ck_connector_label CHECK (label IS NULL OR label ~ '^[[:alnum:]_.:-]{1,255}$'),
    ADD CONSTRAINT ck_connector_ancestor CHECK (ancestor_id IS NULL OR ancestor_id > 0),
    ADD CONSTRAINT ck_connector_page_size CHECK (page_size BETWEEN 1 AND 250);

-- The exact number of deletions the deletion safety held back (ADR-0042's open point) and whether the run listed the
-- whole source or only what changed. NULL on runs written before this migration: the panel then falls back to
-- "deferred" as an upper bound.
ALTER TABLE connector.sync_run
    ADD COLUMN pending_deletions integer,
    ADD COLUMN listing_mode      text    NOT NULL DEFAULT 'FULL',
    ADD CONSTRAINT ck_sync_run_pending_deletions CHECK (pending_deletions IS NULL OR pending_deletions >= 0),
    ADD CONSTRAINT ck_sync_run_listing_mode CHECK (listing_mode IN ('FULL', 'INCREMENTAL'));
