package com.verso.audit.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.dto.AuditRecord;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditTargetType;
import com.verso.audit.repository.AuditEventRepository;
import com.verso.audit.repository.AuditEventRepository.NewEntry;
import com.verso.audit.service.AuditMetrics;
import com.verso.platform.security.authority.CallerRoles;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The durable path's audit side (ADR-0039): capture takes who and where from the request (ids only), store is
 * idempotent by the event id and counts only the row it wrote. The unique key itself is tested against PostgreSQL in
 * verso-app's AuditOutboxTest (CI).
 */
class AuditTrailServiceImplTest {

    private final AuditEventRepository repository = mock(AuditEventRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CallerRoles roles = mock(CallerRoles.class);
    private final AuditTrailServiceImpl trail =
            new AuditTrailServiceImpl(repository, new AuditMetrics(registry), roles, "LOCAL");
    private final AuditEvent event = AuditEvent.success(AuditAction.DOCUMENT_UPLOADED, AuditTargetType.DOCUMENT,
            UUID.randomUUID());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void capture_whenTheCallerIsVerified_takesSubjectRolesTraceAndMode() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub-7").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build();
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES);
        when(roles.of(token)).thenReturn(List.of("verso-admin"));
        SecurityContextHolder.getContext().setAuthentication(token);

        AuditRecord record = trail.capture(event);

        assertThat(record.actorSub()).isEqualTo("sub-7");
        assertThat(record.actorRoles()).containsExactly("verso-admin");
        assertThat(record.traceId()).matches("[0-9a-f]{32}");
        assertThat(record.mode()).isEqualTo("local");
        assertThat(record.eventId()).isNotNull();
    }

    @Test
    void capture_withoutAnAuthenticatedCaller_isRefused() {
        assertThatThrownBy(() -> trail.capture(event)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void store_whenTheSameRecordArrivesTwice_countsOnlyTheRowItWrote() {
        AuditRecord record = new AuditRecord(UUID.randomUUID(), "sub-7", List.of("verso-user"),
                "0123456789abcdef0123456789abcdef", "local", event);
        when(repository.insertIfAbsent(any())).thenReturn(true, false);

        assertThat(trail.store(record)).isTrue();
        assertThat(trail.store(record)).as("duplicate delivery").isFalse();

        ArgumentCaptor<NewEntry> written = ArgumentCaptor.forClass(NewEntry.class);
        verify(repository, org.mockito.Mockito.times(2)).insertIfAbsent(written.capture());
        assertThat(written.getAllValues()).extracting(NewEntry::eventId).containsOnly(record.eventId());
        assertThat(registry.get("verso.audit.events").tag("action", "document_uploaded").counter().count()).isOne();
    }

    @Test
    void record_whenAValueTheAuditTableWouldRefuse_isRefusedBeforeTheOutbox() {
        assertThatThrownBy(() -> new AuditRecord(UUID.randomUUID(), "sub", List.of("hr-group"),
                "0123456789abcdef0123456789abcdef", "local", event)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditRecord(UUID.randomUUID(), "sub", List.of(), "not-a-trace", "local", event))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditRecord(UUID.randomUUID(), "", List.of(), "0123456789abcdef0123456789abcdef",
                "local", event)).isInstanceOf(IllegalArgumentException.class);
    }
}
