package com.verso.qa.api;

import com.verso.qa.api.dto.SubjectConversation;
import com.verso.qa.api.dto.SubjectHistoryCounts;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * In-process contract for KVKK data-subject requests (ADR-0034): the history the qa module holds about one token
 * {@code sub} (conversations, turns, citation references, feedback, per-account activity days), its erasure, and the
 * redaction of answers that cited deleted documents. The anonymous daily counters (qa.usage_daily*) hold no account
 * and are kept. Every method is refused to a caller without {@code verso-admin}, whichever module calls it.
 */
public interface QaSubjects {

    /** At most this many conversations per export batch: 200 turns each (ADR-0023) keeps a batch in memory bounds. */
    int MAX_CONVERSATION_BATCH = 5;

    /** At most this many document ids per redaction call. */
    int MAX_REDACTION_IDS = 1_000;

    /**
     * The fixed text that replaces a redacted answer. It is written by the database function
     * {@code qa.redact_answers_citing} (V2__answer_redaction.sql), never by the application: the two must stay equal
     * (QaSubjectsServiceImplTest).
     */
    String REDACTED_ANSWER = "Bu cevap, alıntı yaptığı belge silindiği için yönetici tarafından kaldırıldı.";

    SubjectHistoryCounts count(String sub);

    /** The subject's conversations with their turns, oldest first by id, after {@code after} (null: from the start). */
    List<SubjectConversation> conversations(String sub, UUID after, int limit);

    /**
     * Deletes the subject's conversations (turns, citation references and feedback go with them) and activity days, in
     * short transactions of the qa schema, and returns what was removed. Idempotent: a repeat removes nothing.
     */
    SubjectHistoryCounts deleteHistory(String sub);

    /**
     * Replaces the answer text of every turn that cites one of {@code documentIds} with {@link #REDACTED_ANSWER},
     * whoever asked it; questions, citation references, feedback and counters stay. Returns the number of answers
     * changed; idempotent (an answer already redacted is not counted again).
     */
    int redactAnswersCiting(Collection<UUID> documentIds);
}
