package com.verso.audit.service.impl;

import com.verso.audit.api.AuditSubjects;
import com.verso.audit.api.dto.AuditSubjectEvent;
import com.verso.audit.repository.AuditEventRepository;
import com.verso.audit.repository.AuditEventRepository.Cursor;
import com.verso.platform.security.authority.VersoRoles;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * {@link AuditSubjects} (ADR-0034): read-only, verso-admin only here as well as at the endpoint (defence in depth,
 * ADR-0019). Nothing in this class can remove a row; the subject's rows stay until the retention removes them.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuditSubjectsServiceImpl implements AuditSubjects {

    private final AuditEventRepository repository;

    public AuditSubjectsServiceImpl(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Override
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public long countByActor(String sub) {
        return repository.countByActor(sub);
    }

    @Override
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public List<AuditSubjectEvent> byActor(String sub, Instant afterAt, UUID afterId, int limit) {
        if (limit < 1 || limit > MAX_BATCH) throw new IllegalArgumentException("limit must be 1.." + MAX_BATCH);
        if ((afterAt == null) != (afterId == null)) throw new IllegalArgumentException("keyset needs both parts");
        return repository.byActor(sub, afterAt == null ? null : new Cursor(afterAt, afterId), limit);
    }
}
