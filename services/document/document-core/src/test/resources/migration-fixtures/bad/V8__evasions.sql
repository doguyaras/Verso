-- Escapes tried against the qualified-name check: quoted on both sides, moving a table out of the schema.
CREATE TABLE "qa"."answer" (id UUID PRIMARY KEY);
ALTER TABLE document.fixture_ok SET SCHEMA qa;
