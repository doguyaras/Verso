package com.verso.mcp.service;

import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditTargetType;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * What the MCP server tells the audit log (ADR-0026, ADR-0031): one event per tool call that read something, with ids
 * and a fixed outcome word only. Never the query, a title or passage text (llm-rules 2.1). The audit module adds who
 * (token sub and roles), when and the trace id. A failure here never fails the tool call.
 */
public class McpAudit {

    private static final Logger log = LoggerFactory.getLogger(McpAudit.class);

    private final ApplicationEventPublisher events;

    public McpAudit(ApplicationEventPublisher events) {
        this.events = events;
    }

    /** RESULTS or NO_RESULTS; the documents whose passages were returned. */
    public void searched(List<UUID> documentIds) {
        publish(() -> AuditEvent.success(AuditAction.MCP_SEARCH, AuditTargetType.PASSAGE)
                .withDetail(documentIds.isEmpty() ? "NO_RESULTS" : "RESULTS")
                .answeredBy(null, null, documentIds));
    }

    /** FOUND with the document read, or NOT_FOUND (unknown and unreadable alike); the passage id when it was one. */
    public void fetched(UUID passageId, UUID documentId) {
        publish(() -> AuditEvent.success(AuditAction.MCP_FETCH, AuditTargetType.PASSAGE, passageId)
                .withDetail(documentId == null ? "NOT_FOUND" : "FOUND")
                .answeredBy(null, null, documentId == null ? List.of() : List.of(documentId)));
    }

    /** RESULTS or NO_RESULTS (ADR-0053): the listing of the caller's approved skills; never a name. */
    public void skillsListed(int count) {
        publish(() -> AuditEvent.success(AuditAction.MCP_SKILLS, AuditTargetType.SKILL)
                .withDetail(count == 0 ? "NO_RESULTS" : "RESULTS"));
    }

    /** FOUND with the skill and its source documents, or NOT_FOUND (unknown and unreadable alike, no id). */
    public void skillRead(UUID skillId, List<UUID> documentIds) {
        publish(() -> AuditEvent.success(AuditAction.MCP_SKILL, AuditTargetType.SKILL, skillId)
                .withDetail(skillId == null ? "NOT_FOUND" : "FOUND")
                .answeredBy(null, null, documentIds));
    }

    private void publish(Supplier<AuditEvent> event) {
        try {
            events.publishEvent(event.get());
        } catch (RuntimeException e) {
            log.error("Audit event not published: code=AUDIT_PUBLISH_FAILED exceptionType={}", e.getClass().getSimpleName());
        }
    }
}
