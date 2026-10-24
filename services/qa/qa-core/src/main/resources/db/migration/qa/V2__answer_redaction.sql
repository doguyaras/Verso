-- Answer redaction after a document is deleted (ADR-0034). A stored answer may quote a document word for word, and the
-- document may hold personal data (KVKK). Deleting the document removes its file, pages, chunks and vectors; the
-- answers that quoted it stay in the history (ADR-0023 section 7). verso-admin can now replace the text of those
-- answers with a fixed Turkish notice; question, citation references, feedback and all counters stay.
--
-- svc_qa has no UPDATE on qa.turn (ADR-0023: turns are written and deleted, never changed), and that stays: this
-- function, owned by the migration role, is the only way to change an answer, and it can only write the fixed notice.
-- The application cannot choose the text, so a compromised application cannot rewrite history through it. Same shape
-- as audit.purge_expired (ADR-0026): SECURITY DEFINER, callable only from a svc_qa session, bounded batches, fully
-- qualified names. Other roles have no USAGE on the schema "qa" and cannot reach it at all.
--
-- The notice must equal QaSubjects.REDACTED_ANSWER (qa-api); QaSubjectsServiceImplTest compares them.
-- Rollback: DROP FUNCTION qa.redact_answers_citing(uuid[], integer) (no data change; redacted answers stay redacted).

CREATE FUNCTION qa.redact_answers_citing(document_ids uuid[], batch_size integer)
    RETURNS integer
    LANGUAGE plpgsql
    SECURITY DEFINER
AS $$
DECLARE
    notice constant text := 'Bu cevap, alıntı yaptığı belge silindiği için yönetici tarafından kaldırıldı.';
    redacted integer;
BEGIN
    IF session_user <> 'svc_qa' THEN
        RAISE EXCEPTION 'qa.redact_answers_citing: caller not allowed' USING ERRCODE = '42501';
    END IF;
    IF document_ids IS NULL OR cardinality(document_ids) < 1 OR cardinality(document_ids) > 1000 THEN
        RAISE EXCEPTION 'qa.redact_answers_citing: 1..1000 document ids' USING ERRCODE = '22023';
    END IF;
    IF batch_size IS NULL OR batch_size < 1 OR batch_size > 1000 THEN
        RAISE EXCEPTION 'qa.redact_answers_citing: batch_size must be 1..1000' USING ERRCODE = '22023';
    END IF;
    -- "answer <> notice" on the row itself too: a concurrent call that redacted the row first is re-checked after its
    -- commit and not counted twice.
    UPDATE qa.turn SET answer = notice
    WHERE answer <> notice AND id IN (SELECT DISTINCT c.turn_id FROM qa.turn_citation c JOIN qa.turn t ON t.id = c.turn_id
                 WHERE c.document_id = ANY (document_ids) AND t.answer <> notice
                 LIMIT batch_size);
    GET DIAGNOSTICS redacted = ROW_COUNT;
    RETURN redacted;
END
$$;
