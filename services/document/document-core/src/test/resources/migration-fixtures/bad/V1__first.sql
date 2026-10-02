-- Clean first migration: the fixture set starts valid, and nothing here may be reported.
CREATE TABLE document.fixture_ok (id UUID PRIMARY KEY, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX idx_fixture_ok_created ON document.fixture_ok (created_at DESC, id DESC);
SELECT f.id FROM document.fixture_ok f JOIN document.fixture_ok g ON g.id = f.id WHERE f.id = g.id;
-- Append-only table: narrowing the application role is allowed (reference 10.1).
REVOKE UPDATE, DELETE ON document.fixture_ok FROM svc_document;
