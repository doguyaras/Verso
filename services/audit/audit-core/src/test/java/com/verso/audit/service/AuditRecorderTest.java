package com.verso.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditTargetType;
import com.verso.audit.config.AuditProperties;
import com.verso.audit.repository.AuditEventRepository;
import com.verso.audit.repository.AuditEventRepository.NewEntry;
import com.verso.platform.security.authority.CallerRoles;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;

/**
 * The recorder never breaks a request (ADR-0026): a failing repository is counted, never rethrown, and the audit
 * database is left alone for the breaker window; a caller without a verified subject is counted as invalid.
 */
class AuditRecorderTest {

    private final AuditEventRepository repository = mock(AuditEventRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AuditMetrics metrics = new AuditMetrics(registry);
    private final CallerRoles roles = mock(CallerRoles.class);
    private final AuditEvent event = AuditEvent.success(AuditAction.DOCUMENT_DELETED, AuditTargetType.DOCUMENT, UUID.randomUUID());

    private AuditRecorder recorder(Duration breakerOpen) {
        AuditProperties properties = new AuditProperties(365, false, Duration.ofHours(6), Duration.ofMinutes(10), 1000, 100,
                100, 366, ZoneId.of("UTC"), breakerOpen);
        return new AuditRecorder(repository, metrics, roles, properties, "LOCAL");
    }

    private JwtAuthenticationToken caller() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub-1").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build();
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES);
        when(roles.of(token)).thenReturn(List.of("verso-user"));
        return token;
    }

    private double notStored(String reason) {
        return registry.get("verso.audit.events.not.stored").tag("reason", reason).counter().count();
    }

    @Test
    void record_whenTheCallerIsVerified_writesTheSubjectRolesAndTheInstallationMode() {
        recorder(Duration.ofSeconds(10)).record(event, caller());

        ArgumentCaptor<NewEntry> written = ArgumentCaptor.forClass(NewEntry.class);
        verify(repository).insert(written.capture());
        assertThat(written.getValue().actorSub()).isEqualTo("sub-1");
        assertThat(written.getValue().actorRoles()).containsExactly("verso-user");
        assertThat(written.getValue().mode()).as("verso.ai.mode, lower case").isEqualTo("local");
        assertThat(written.getValue().traceId()).matches("[0-9a-f]{32}");
        assertThat(registry.get("verso.audit.events").tag("action", "document_deleted").counter().count()).isOne();
    }

    @Test
    void record_whenTheWriteFails_isCountedNotRethrownAndTheDatabaseIsLeftAloneForTheBreakerWindow() {
        doThrow(new DataAccessResourceFailureException("password=hunter2 jdbc:postgresql://db")).when(repository).insert(any());
        AuditRecorder recorder = recorder(Duration.ofMinutes(5));
        JwtAuthenticationToken caller = caller();

        assertThatCode(() -> recorder.record(event, caller)).doesNotThrowAnyException();
        assertThatCode(() -> recorder.record(event, caller)).doesNotThrowAnyException();
        assertThatCode(() -> recorder.record(event, caller)).doesNotThrowAnyException();

        verify(repository, times(1)).insert(any());
        assertThat(notStored("failed")).isOne();
        assertThat(notStored("circuit_open")).as("the two events skipped while the breaker is open").isEqualTo(2);
    }

    @Test
    void record_whenTheBreakerWindowIsOver_triesTheDatabaseAgain() {
        doThrow(new DataAccessResourceFailureException("down")).when(repository).insert(any());
        AuditRecorder recorder = recorder(Duration.ZERO);

        recorder.record(event, caller());
        recorder.record(event, caller());

        verify(repository, times(2)).insert(any());
        assertThat(notStored("failed")).isEqualTo(2);
    }

    @Test
    void record_whenThereIsNoVerifiedSubject_isCountedAsInvalidAndNothingIsWritten() {
        AuditRecorder recorder = recorder(Duration.ofSeconds(10));

        assertThatCode(() -> recorder.record(event, null)).doesNotThrowAnyException();
        assertThatCode(() -> recorder.record(event, new TestingAuthenticationToken("someone", "x"))).doesNotThrowAnyException();

        verify(repository, never()).insert(any());
        assertThat(notStored("invalid")).isEqualTo(2);
    }

    @Test
    void constructor_whenTheModeIsNeitherLocalNorCloud_doesNotStart() {
        AuditProperties properties = new AuditProperties(365, false, Duration.ofHours(6), Duration.ofMinutes(10), 1000, 100, 100,
                366, ZoneId.of("UTC"), Duration.ZERO);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AuditRecorder(repository, metrics, roles, properties, "hybrid"))
                .hasMessageContaining("verso.ai.mode");
    }
}
