-- Touches other schemas, plain, quoted and in a comma join.
CREATE TABLE qa.answer (id UUID PRIMARY KEY);
INSERT INTO public.audit_copy SELECT * FROM document.fixture_ok;
CREATE TABLE "quoted".answer2 (id UUID PRIMARY KEY);
SELECT f.id FROM document.fixture_ok f, audit.log l WHERE l.id = f.id;
