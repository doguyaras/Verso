package com.verso.audit.service;

import com.verso.audit.api.dto.AuditPageResponse;
import com.verso.audit.api.enums.AuditAction;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Reads of the audit log for verso-admin (ADR-0026): a filtered page and a capped CSV export. {@code from} and
 * {@code to} are inclusive calendar days in {@code verso.audit.time-zone}; a window beyond
 * {@code verso.audit.max-range-days} (or a reversed one) is 12001 AUDIT_RANGE_INVALID.
 */
public interface AuditQueryService {

    /** An export to run: the validated window and whether the row cap will cut it. */
    record ExportPlan(Instant from, Instant to, boolean truncated, int maxRows) {
    }

    AuditPageResponse page(LocalDate from, LocalDate to, AuditAction action, int page, int size);

    /** Validates the window and tells, before any byte is written, whether the cap will cut the export. */
    ExportPlan planExport(LocalDate from, LocalDate to);

    /** Writes the header and the rows, oldest first; returns the rows written (at most {@code plan.maxRows()}). */
    int writeExport(ExportPlan plan, Writer out) throws IOException;
}
