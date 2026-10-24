-- Shared collections with permission-aware retrieval (ADR-0028). A document lives either in its owner's personal space
-- (collection_id NULL, today's behaviour) or in one collection that verso-admin manages. Who may read a collection is a
-- list of principals: an IdP group by its full path ("group:/hukuk/sozlesmeler") or a user by the token's sub
-- ("user:<sub>"). Retrieval never reads the grants directly: every document carries its own denormalised principal
-- list (document_principal), so the access filter is one indexed EXISTS inside the vector and full-text queries
-- (llm-rules 4.1). Chunks are not touched: the application role cannot update them (V1), and a grant change rewriting
-- 1024-dimension rows and their HNSW entries would cost seconds per collection (docs/evidence/paylasimli-alanlar-dogrulama.md).
--
-- Rollout: new tables, a nullable column without default (catalogue change) and a foreign key validated against a
-- column that is NULL everywhere; the only data step is one principal row per existing document. All of it runs in the
-- one-shot migrate step before the application starts.

CREATE TABLE document.collection (
    id           uuid        NOT NULL DEFAULT uuidv7(),
    -- Free text an admin typed: shown to readers, never logged (llm-rules 2.1).
    name         text        NOT NULL,
    description  text,
    -- The token sub of the admin who created it (ADR-0005); kept for the record, it grants nothing.
    created_by   text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_collection PRIMARY KEY (id),
    CONSTRAINT ck_collection_name CHECK (char_length(name) BETWEEN 1 AND 100),
    CONSTRAINT ck_collection_description CHECK (description IS NULL OR char_length(description) <= 500)
);
-- Two collections with the same name would be indistinguishable in a picker or on a citation badge.
CREATE UNIQUE INDEX uq_collection_name ON document.collection (lower(name));

-- Who may read a collection. A principal is "user:<sub>" or "group:<group claim value>" (ADR-0019 bounds both to 256
-- characters); there is no deny entry and no role entry: a grant only adds readers (fail closed).
CREATE TABLE document.collection_grant (
    id             uuid        NOT NULL DEFAULT uuidv7(),
    collection_id  uuid        NOT NULL,
    principal      text        NOT NULL,
    created_by     text        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_collection_grant PRIMARY KEY (id),
    CONSTRAINT fk_collection_grant_collection FOREIGN KEY (collection_id)
        REFERENCES document.collection (id) ON DELETE CASCADE,
    CONSTRAINT uq_collection_grant_principal UNIQUE (collection_id, principal),
    CONSTRAINT ck_collection_grant_principal CHECK (principal ~ '^(user|group):[^[:cntrl:]]+$' AND char_length(principal) <= 262)
);
-- GET /v1/collections for a reader: the collections granted to any of the caller's principals.
CREATE INDEX idx_collection_grant_principal ON document.collection_grant (principal, collection_id);

-- NULL: the uploader's personal space. RESTRICT (the default): a collection that still holds documents cannot be
-- deleted; the service answers 409 first, the key is the backstop against a concurrent upload.
ALTER TABLE document.document ADD COLUMN collection_id uuid;
ALTER TABLE document.document ADD CONSTRAINT fk_document_collection FOREIGN KEY (collection_id)
    REFERENCES document.collection (id);
-- GET /v1/documents?collectionId=: one collection's documents, newest first; also serves the foreign key check.
CREATE INDEX idx_document_collection_created ON document.document (collection_id, created_at DESC, id DESC)
    WHERE collection_id IS NOT NULL;

-- Who may read each document: "user:<owner sub>" for a personal document, the collection's grants for a collection
-- document. Written in the same transaction as the upload, the move and the grant change that decide it, under the
-- collection row lock (DocumentRepository, CollectionRepository); deleted with the document.
CREATE TABLE document.document_principal (
    document_id  uuid NOT NULL,
    principal    text NOT NULL,
    CONSTRAINT pk_document_principal PRIMARY KEY (document_id, principal),
    CONSTRAINT fk_document_principal_document FOREIGN KEY (document_id)
        REFERENCES document.document (id) ON DELETE CASCADE,
    CONSTRAINT ck_document_principal CHECK (principal ~ '^(user|group):[^[:cntrl:]]+$' AND char_length(principal) <= 262)
);
-- The caller's documents (list, count, term-share denominator): principal = ANY(:principals) first.
CREATE INDEX idx_document_principal_principal ON document.document_principal (principal, document_id);

-- Every existing document is personal: its owner is its only reader, exactly as before.
INSERT INTO document.document_principal (document_id, principal)
SELECT id, 'user:' || account_id FROM document.document;

-- Grants and readers are inserted and deleted, never updated (db review): the application role gets no UPDATE on them
-- (the default privileges grant it), like files, pages and chunks in V1.
REVOKE UPDATE ON document.collection_grant FROM svc_document;
REVOKE UPDATE ON document.document_principal FROM svc_document;
