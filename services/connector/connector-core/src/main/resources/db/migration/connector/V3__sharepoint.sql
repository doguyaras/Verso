-- SharePoint Online / OneDrive connector over Microsoft Graph (ADR-0045) on the same tables as SMB (ADR-0038) and
-- Confluence (ADR-0044): a connector row gets the type SHAREPOINT and its app registration; base_url holds the site URL,
-- path_prefix the folder inside each library, skip_restricted the v1 safety rule (files with unique permissions are not
-- synced). Additive: SMB and Confluence rows keep every value and every check.
--
-- Still no secret is stored: credential_ref names the secret file (the app's private key and certificate, or its client
-- secret). tenant_id and client_id are directory and application GUIDs, not credentials. connector_item keeps
-- "drives/<drive id>/items/<item id>" as paths, never a file name.

-- contract: the DROP only widens the type check by one value; every row the previous release writes still satisfies
-- it, so the previous release runs on this schema.
ALTER TABLE connector.connector DROP CONSTRAINT ck_connector_type;
ALTER TABLE connector.connector ADD CONSTRAINT ck_connector_type CHECK (type IN ('SMB', 'CONFLUENCE', 'SHAREPOINT'));

ALTER TABLE connector.connector
    -- The Graph site id (host,site-guid,web-guid) when the admin gave it instead of (or with) the site URL.
    ADD COLUMN site_id     text,
    -- Library names or drive ids; empty: the site's default library.
    ADD COLUMN drive_names text[] NOT NULL DEFAULT '{}',
    ADD COLUMN tenant_id   text,
    ADD COLUMN client_id   text,
    ADD COLUMN auth_method text,
    ADD CONSTRAINT ck_connector_sharepoint_fields
        CHECK (type <> 'SHAREPOINT' OR ((base_url IS NOT NULL OR site_id IS NOT NULL) AND tenant_id IS NOT NULL
            AND client_id IS NOT NULL AND auth_method IS NOT NULL)),
    ADD CONSTRAINT ck_connector_site_id
        CHECK (site_id IS NULL OR site_id ~ '^[a-z0-9.-]{1,253},[0-9a-f-]{36},[0-9a-f-]{36}$'),
    ADD CONSTRAINT ck_connector_drive_names CHECK (cardinality(drive_names) <= 10),
    ADD CONSTRAINT ck_connector_tenant_id
        CHECK (tenant_id IS NULL OR tenant_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    ADD CONSTRAINT ck_connector_client_id
        CHECK (client_id IS NULL OR client_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    ADD CONSTRAINT ck_connector_auth_method CHECK (auth_method IS NULL OR auth_method IN ('CERTIFICATE', 'CLIENT_SECRET'));

-- Per library, where the last fully applied run left the Graph delta: the link to continue from (opaque state, no
-- credential) and the library root's id. Replaced by a full listing; a connector's rows go with it.
CREATE TABLE connector.sharepoint_drive (
    connector_id uuid        NOT NULL,
    drive_id     text        NOT NULL,
    root_id      text        NOT NULL,
    delta_link   text        NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_sharepoint_drive PRIMARY KEY (connector_id, drive_id),
    CONSTRAINT fk_sharepoint_drive_connector FOREIGN KEY (connector_id)
        REFERENCES connector.connector (id) ON DELETE CASCADE,
    CONSTRAINT ck_sharepoint_drive_id CHECK (drive_id ~ '^[A-Za-z0-9!_-]{1,200}$'),
    CONSTRAINT ck_sharepoint_drive_root CHECK (root_id ~ '^[A-Za-z0-9!_-]{1,200}$'),
    CONSTRAINT ck_sharepoint_drive_delta_link CHECK (char_length(delta_link) BETWEEN 1 AND 8192)
);

-- The folder tree of each library: delta reports a renamed or moved folder without its children and without paths, so
-- a file's path (folder filter, globs) is built from these rows. The name is kept for that only; it is never logged and
-- never returned by the API.
CREATE TABLE connector.sharepoint_folder (
    connector_id uuid NOT NULL,
    drive_id     text NOT NULL,
    item_id      text NOT NULL,
    parent_id    text NOT NULL,
    name         text NOT NULL,
    CONSTRAINT pk_sharepoint_folder PRIMARY KEY (connector_id, drive_id, item_id),
    CONSTRAINT fk_sharepoint_folder_drive FOREIGN KEY (connector_id, drive_id)
        REFERENCES connector.sharepoint_drive (connector_id, drive_id) ON DELETE CASCADE,
    CONSTRAINT ck_sharepoint_folder_item CHECK (item_id ~ '^[A-Za-z0-9!_-]{1,200}$'),
    CONSTRAINT ck_sharepoint_folder_parent CHECK (parent_id ~ '^[A-Za-z0-9!_-]{1,200}$'),
    CONSTRAINT ck_sharepoint_folder_name CHECK (char_length(name) BETWEEN 1 AND 400)
);
