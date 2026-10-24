package com.verso.audit.api.dto;

import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditOutcome;
import com.verso.audit.api.enums.AuditTargetType;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What a module tells the audit log (ADR-0026), published as a Spring application event after the business operation
 * committed. It is "ids only" by construction (security-rules 4/5): enums, UUIDs and short fixed tokens; there is no
 * field that could carry a question, an answer, a file name, an address or any other free text, and the constructor
 * rejects a token that does not look like a fixed word. The audit module adds who (token subject and roles), when,
 * the request's trace id and the installation's AI mode; the publisher does not and cannot set them.
 *
 * @param action               what happened
 * @param outcome              how it ended
 * @param targetType           the kind of target
 * @param targetId             the target's id (a document, conversation or turn); null when there is none or it is
 *                             unknown (a question whose turn could not be stored)
 * @param conversationId       the conversation of a turn or rating
 * @param detailCode           a fixed UPPER_SNAKE word: the answer outcome of a question, the rating of a feedback, the
 *                             endpoint of a denial
 * @param modelId              the chat model that answered, as configured (not user input)
 * @param mode                 "local" or "cloud" as the answer reported it; null: the installation's current mode
 * @param citationDocumentIds  ids of the documents the answer cited (at most {@value #MAX_CITATIONS})
 */
public record AuditEvent(AuditAction action, AuditOutcome outcome, AuditTargetType targetType, UUID targetId,
                         UUID conversationId, String detailCode, String modelId, String mode,
                         List<UUID> citationDocumentIds) {

    public static final int MAX_CITATIONS = 50;
    private static final Pattern DETAIL = Pattern.compile("[A-Z0-9_]{1,40}");
    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9._:/@+-]{1,100}");

    public AuditEvent {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(targetType, "targetType");
        if (detailCode != null && !DETAIL.matcher(detailCode).matches()) {
            throw new IllegalArgumentException("detailCode must be a fixed UPPER_SNAKE word");
        }
        if (modelId != null && !MODEL.matcher(modelId).matches()) {
            throw new IllegalArgumentException("modelId must look like a model identifier");
        }
        if (mode != null && !mode.equals("local") && !mode.equals("cloud")) {
            throw new IllegalArgumentException("mode must be local or cloud");
        }
        citationDocumentIds = citationDocumentIds == null ? List.of()
                : citationDocumentIds.stream().distinct().limit(MAX_CITATIONS).toList();
    }

    /** A successful operation on one target. */
    public static AuditEvent success(AuditAction action, AuditTargetType targetType, UUID targetId) {
        return new AuditEvent(action, AuditOutcome.SUCCESS, targetType, targetId, null, null, null, null, List.of());
    }

    /** A successful operation without a single target (delete-all, metrics, export). */
    public static AuditEvent success(AuditAction action, AuditTargetType targetType) {
        return success(action, targetType, null);
    }

    /** The same event with a detail word (rating, answer outcome). */
    public AuditEvent withDetail(String detail) {
        return new AuditEvent(action, outcome, targetType, targetId, conversationId, detail, modelId, mode,
                citationDocumentIds);
    }

    /** The same event inside a conversation. */
    public AuditEvent inConversation(UUID conversation) {
        return new AuditEvent(action, outcome, targetType, targetId, conversation, detailCode, modelId, mode,
                citationDocumentIds);
    }

    /** The same event with the model that answered, the mode it reported and the documents it cited. */
    public AuditEvent answeredBy(String model, String answerMode, List<UUID> cited) {
        return new AuditEvent(action, outcome, targetType, targetId, conversationId, detailCode, model, answerMode, cited);
    }
}
