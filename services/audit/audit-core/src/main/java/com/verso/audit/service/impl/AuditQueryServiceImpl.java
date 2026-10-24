package com.verso.audit.service.impl;

import com.verso.audit.api.dto.AuditEntryResponse;
import com.verso.audit.api.dto.AuditPageResponse;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.config.AuditProperties;
import com.verso.audit.exception.AuditErrorCode;
import com.verso.audit.exception.AuditServiceException;
import com.verso.audit.repository.AuditEventRepository;
import com.verso.audit.repository.AuditEventRepository.Cursor;
import com.verso.audit.service.AuditCsv;
import com.verso.audit.service.AuditQueryService;
import com.verso.audit.service.AuditQueryService.ExportPlan;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

/**
 * Reads of the audit log for verso-admin (ADR-0026): a filtered page and a capped CSV export. {@code from} and
 * {@code to} are inclusive calendar days in {@code verso.audit.time-zone}; the window is at most
 * {@code verso.audit.max-range-days} days. A database failure is not caught here: it reaches the global handler as
 * 503 SERVICE_UNAVAILABLE like every other store.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuditQueryServiceImpl implements AuditQueryService {

    private final AuditEventRepository repository;
    private final AuditProperties properties;

    public AuditQueryServiceImpl(AuditEventRepository repository, AuditProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public AuditPageResponse page(LocalDate from, LocalDate to, AuditAction action, int page, int size) {
        Instant start = start(from, to);
        Instant end = end(to);
        List<AuditEntryResponse> rows = repository.page(start, end, action, page * size, size);
        return AuditPageResponse.of(rows, page, size, repository.count(start, end, action));
    }

    @Override
    public ExportPlan planExport(LocalDate from, LocalDate to) {
        Instant start = start(from, to);
        Instant end = end(to);
        boolean truncated = repository.countUpTo(start, end, properties.exportMaxRows()) > properties.exportMaxRows();
        return new ExportPlan(start, end, truncated, properties.exportMaxRows());
    }

    /**
     * Writes the header and the rows, oldest first, in keyset batches (each its own short statement, so the stream
     * holds no connection and no transaction between batches). Stops at the row cap. Returns the rows written.
     */
    @Override
    public int writeExport(ExportPlan plan, Writer out) throws IOException {
        AuditCsv.writeHeader(out);
        int written = 0;
        Cursor cursor = null;
        while (written < plan.maxRows()) {
            int want = Math.min(properties.exportBatchSize(), plan.maxRows() - written);
            List<AuditEntryResponse> batch = repository.exportBatch(plan.from(), plan.to(), cursor, want);
            for (AuditEntryResponse row : batch) AuditCsv.writeRow(out, row);
            written += batch.size();
            if (batch.size() < want) break;
            AuditEntryResponse last = batch.get(batch.size() - 1);
            cursor = new Cursor(last.occurredAt(), last.id());
            out.flush();
        }
        return written;
    }

    private Instant start(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > properties.maxRangeDays()) {
            throw new AuditServiceException(AuditErrorCode.AUDIT_RANGE_INVALID, "AUDIT_RANGE_INVALID");
        }
        return from.atStartOfDay(properties.timeZone()).toInstant();
    }

    private Instant end(LocalDate to) {
        return to.plusDays(1).atStartOfDay(properties.timeZone()).toInstant();
    }
}
