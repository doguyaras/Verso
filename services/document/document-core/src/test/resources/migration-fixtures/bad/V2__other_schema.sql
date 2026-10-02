-- Touches other schemas.
CREATE TABLE qa.answer (id UUID PRIMARY KEY);
INSERT INTO public.audit_copy SELECT * FROM document.fixture_ok;
