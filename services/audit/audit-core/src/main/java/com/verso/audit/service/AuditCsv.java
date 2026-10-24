package com.verso.audit.service;

import com.verso.audit.api.dto.AuditEntryResponse;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CSV of the audit log (RFC 4180, CRLF lines). A spreadsheet runs a cell that starts with {@code = + - @} (and a tab or
 * carriage return, which some programs strip before looking) as a formula, so such a cell is prefixed with an
 * apostrophe, the OWASP "CSV injection" mitigation. The values are ids, fixed words and the token subject; the subject
 * is the only one an outsider (the identity provider's tenant) influences, which is why every cell goes through the
 * same guard.
 */
public final class AuditCsv {

    public static final String HEADER = "id,occurred_at,actor,actor_roles,action,outcome,target_type,target_id,"
            + "conversation_id,detail_code,model_id,citation_document_ids,trace_id,mode\r\n";

    private AuditCsv() {
    }

    public static void writeHeader(Writer out) throws IOException {
        out.write(HEADER);
    }

    public static void writeRow(Writer out, AuditEntryResponse e) throws IOException {
        String[] cells = {
                str(e.id()), str(e.occurredAt()), e.actor(), String.join(" ", e.actorRoles()), e.action().name(),
                e.outcome().name(), e.targetType().name(), str(e.targetId()), str(e.conversationId()), e.detailCode(),
                e.modelId(), join(e.citationDocumentIds()), e.traceId(), e.mode()};
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.write(',');
            out.write(cell(cells[i]));
        }
        out.write("\r\n");
    }

    /** One cell, guarded against formulas and quoted when it holds a separator, a quote or a line break. */
    static String cell(String value) {
        if (value == null || value.isEmpty()) return "";
        String safe = value;
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            safe = "'" + value;
        }
        boolean quote = safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0 || safe.indexOf('\n') >= 0
                || safe.indexOf('\r') >= 0;
        return quote ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static String join(List<?> values) {
        return values.stream().map(Object::toString).collect(Collectors.joining(" "));
    }
}
