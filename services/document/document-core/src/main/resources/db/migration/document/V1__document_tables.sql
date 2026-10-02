-- Phase 4 (ADR-0011): uploaded documents, their pages and embedded chunks. Owner: the document module; the qa module
-- reads chunks only through document-api (ADR-0003). Every table hangs off document with ON DELETE CASCADE on
-- purpose: deleting a document removes its file, pages, chunks and vectors in one statement (KVKK erasure,
-- llm-rules 4.2). Ids are UUIDv7 from PostgreSQL 18 (reference 10.3: one generation method, no manual assignment).

-- One uploaded document and its ingestion job (ADR-0003: the row is the job; no broker).
CREATE TABLE document.document (
    id                uuid        NOT NULL DEFAULT uuidv7(),
    -- The validated token's sub (ADR-0005); every query filters on it.
    account_id        text        NOT NULL,
    -- The caller's file name, kept to show and cite the document; never logged (llm-rules 2.1).
    file_name         text        NOT NULL,
    size_bytes        bigint      NOT NULL,
    -- Client retry key (reference 6.4); unique per account when present.
    idempotency_key   uuid,
    status            text        NOT NULL DEFAULT 'PENDING',
    failure_reason    text,
    page_count        integer,
    chunk_count       integer,
    -- Model that produced the stored vectors (llm-rules 6.1); set when the document becomes READY.
    embedding_model   text,
    -- Ingestion job (reference 11.1): attempts, backoff and a lease guarded by claim_token.
    attempts          integer     NOT NULL DEFAULT 0,
    next_attempt_at   timestamptz NOT NULL DEFAULT now(),
    locked_until      timestamptz,
    claim_token       uuid,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_document PRIMARY KEY (id),
    CONSTRAINT ck_document_status CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')),
    CONSTRAINT ck_document_failure_reason CHECK (failure_reason IN
        ('NOT_A_PDF', 'ENCRYPTED', 'TOO_MANY_PAGES', 'TOO_MUCH_TEXT', 'UNSUPPORTED_PDF', 'NO_TEXT',
         'PROCESSING_FAILED')),
    -- A reason exactly when FAILED; a lease exactly when PROCESSING.
    CONSTRAINT ck_document_failure_when_failed CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),
    CONSTRAINT ck_document_claim_when_processing CHECK
        ((status = 'PROCESSING') = (claim_token IS NOT NULL AND locked_until IS NOT NULL)),
    CONSTRAINT ck_document_file_name CHECK (char_length(file_name) BETWEEN 1 AND 255),
    CONSTRAINT ck_document_size CHECK (size_bytes > 0),
    CONSTRAINT ck_document_attempts CHECK (attempts >= 0)
);
-- GET /v1/documents: the caller's documents, newest first.
CREATE INDEX idx_document_account_created ON document.document (account_id, created_at DESC, id DESC);
-- Worker claim (reference 11.1, 10.2): due PENDING rows and expired PROCESSING leases only.
CREATE INDEX idx_document_claim ON document.document (status, next_attempt_at, locked_until)
    WHERE status IN ('PENDING', 'PROCESSING');
-- POST /v1/documents with X-Idempotency-Key: the first request wins (reference 6.4).
CREATE UNIQUE INDEX uq_document_account_idempotency_key ON document.document (account_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- The uploaded PDF, only until it has been parsed (ADR-0011, user decision 2026-10-02: data minimisation). A separate
-- table so that listing documents never reads the bytes.
CREATE TABLE document.document_file (
    document_id  uuid  NOT NULL,
    content      bytea NOT NULL,
    CONSTRAINT pk_document_file PRIMARY KEY (document_id),
    CONSTRAINT fk_document_file_document FOREIGN KEY (document_id) REFERENCES document.document (id) ON DELETE CASCADE
);
-- PDF streams are compressed already: store out of line without trying pglz on up to 20 MB (reference 10.6).
ALTER TABLE document.document_file ALTER COLUMN content SET STORAGE EXTERNAL;

-- Extracted text per page: the source of chunks and citations (llm-rules 6.3). Kept instead of the PDF, so chunking
-- and embedding can be redone without the file.
CREATE TABLE document.document_page (
    document_id  uuid    NOT NULL,
    page_number  integer NOT NULL,
    content      text    NOT NULL,
    CONSTRAINT pk_document_page PRIMARY KEY (document_id, page_number),
    CONSTRAINT fk_document_page_document FOREIGN KEY (document_id) REFERENCES document.document (id) ON DELETE CASCADE,
    CONSTRAINT ck_document_page_number CHECK (page_number >= 1)
);

-- Retrieval unit: a piece of one page with its embedding (llm-rules 6.1-6.4). account_id is copied from the document
-- so that the ownership filter is part of the vector query itself (llm-rules 4.1), not a join or a filter in memory.
CREATE TABLE document.document_chunk (
    id               uuid                     NOT NULL DEFAULT uuidv7(),
    document_id      uuid                     NOT NULL,
    account_id       text                     NOT NULL,
    page_number      integer                  NOT NULL,
    chunk_index      integer                  NOT NULL,
    content          text                     NOT NULL,
    embedding        extensions.vector(1024)  NOT NULL,
    embedding_model  text                     NOT NULL,
    CONSTRAINT pk_document_chunk PRIMARY KEY (id),
    CONSTRAINT fk_document_chunk_document FOREIGN KEY (document_id) REFERENCES document.document (id) ON DELETE CASCADE,
    CONSTRAINT uq_document_chunk_position UNIQUE (document_id, chunk_index),
    CONSTRAINT ck_document_chunk_page CHECK (page_number >= 1)
);
-- Retrieval (phase 5): nearest neighbours by cosine distance (bge-m3 vectors are compared by cosine).
CREATE INDEX idx_document_chunk_embedding ON document.document_chunk
    USING hnsw (embedding extensions.vector_cosine_ops);
-- Retrieval's ownership filter for accounts with few chunks, where the planner prefers it over the HNSW scan.
CREATE INDEX idx_document_chunk_account ON document.document_chunk (account_id);

-- Files, pages and chunks are written and deleted, never updated: the application role gets no UPDATE on them
-- (phase 4 db review D6; the default privileges grant it).
REVOKE UPDATE ON document.document_file FROM svc_document;
REVOKE UPDATE ON document.document_page FROM svc_document;
REVOKE UPDATE ON document.document_chunk FROM svc_document;
