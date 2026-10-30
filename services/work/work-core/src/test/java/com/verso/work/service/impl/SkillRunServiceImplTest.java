package com.verso.work.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.verso.audit.api.enums.AuditAction;
import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.Reader;
import com.verso.work.api.SkillModel;
import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.api.dto.SkillRunResponse;
import com.verso.work.api.enums.SkillOrigin;
import com.verso.work.api.enums.SkillRunStatus;
import com.verso.work.config.WorkProperties;
import com.verso.work.domain.TestSkills;
import com.verso.work.exception.WorkErrorCode;
import com.verso.work.exception.WorkServiceException;
import com.verso.work.outbox.WorkAudit;
import com.verso.work.repository.SkillRunRepository;
import com.verso.work.repository.SkillRunRepository.RunRow;
import com.verso.work.repository.WorkDatabase;
import com.verso.work.service.SkillCatalog;
import com.verso.work.service.SkillRunner;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starting a run (ADR-0057): the one-RUNNING-per-owner rule is the repository's answer inside the transaction, never
 * an exception out of it; a refused start gives the model slot back and submits nothing, an accepted one keeps the
 * slot for the background run.
 */
class SkillRunServiceImplTest {

    private final SkillCatalog catalog = mock(SkillCatalog.class);
    private final SkillModel model = mock(SkillModel.class);
    private final SkillRunner runner = mock(SkillRunner.class);
    private final SkillRunRepository runs = mock(SkillRunRepository.class);
    private final WorkDatabase db = mock(WorkDatabase.class);
    private final WorkAudit audit = mock(WorkAudit.class);
    private final DocumentRetrieval retrieval = mock(DocumentRetrieval.class);
    private final WorkProperties properties = new WorkProperties(1, Duration.ofSeconds(1), Duration.ofSeconds(5), 3,
            Duration.ofSeconds(30), 90, 500, 40, Duration.ofMillis(100), Duration.ofMinutes(1));
    private final JsonMapper json = JsonMapper.builder().build();
    private final SkillRunServiceImpl service = new SkillRunServiceImpl(catalog, model, runner, runs, db, audit,
            retrieval, properties, json);
    private final Reader reader = new Reader("acct-1", List.of("/ik"));
    private final SkillDefinition skill = TestSkills.valid();
    private final AtomicBoolean leaseClosed = new AtomicBoolean();
    private final SkillModel.Lease lease = () -> leaseClosed.set(true);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(catalog.require(skill.id())).thenReturn(new SkillCatalog.Entry(skill, SkillOrigin.BUILT_IN, null));
        when(model.tryAcquire(any())).thenReturn(Optional.of(lease));
        when(db.transactionally(any())).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(0)).get());
    }

    @Test
    void start_whenTheOwnerAlreadyHasARunningRun_answers409AndFreesTheSlot() {
        when(runs.insertRunning(any(), eq("acct-1"), eq(skill.id()), anyString(), any(), anyString(), anyInt(),
                anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.start(reader, skill.id(), Map.of("notes", "Toplantı notları.")))
                .isInstanceOf(WorkServiceException.class)
                .satisfies(e -> assertThat(((WorkServiceException) e).getErrorCode()).isEqualTo(WorkErrorCode.RUN_ACTIVE));

        assertThat(leaseClosed).as("the slot goes back").isTrue();
        verify(runner, never()).submit(any());
        verify(audit, never()).run(any(), any(), any());
    }

    @Test
    void start_whenAccepted_auditsSubmitsAndKeepsTheSlot() {
        when(runs.insertRunning(any(), eq("acct-1"), eq(skill.id()), anyString(), any(), anyString(), anyInt(),
                anyString(), any())).thenReturn(true);
        when(runs.find(any(), eq("acct-1"))).thenAnswer(inv -> Optional.of(running(inv.getArgument(0))));
        when(runs.sources(any())).thenReturn(List.of());

        SkillRunResponse response = service.start(reader, skill.id(), Map.of("notes", "Toplantı notları."));

        assertThat(response.status()).isEqualTo(SkillRunStatus.RUNNING);
        assertThat(leaseClosed).as("the background run holds the slot").isFalse();
        verify(audit).run(eq(AuditAction.TASK_SKILL_RUN_STARTED), eq(response.id()), eq("BUILT_IN"));
        verify(runner).submit(any(SkillRunner.Job.class));
    }

    private RunRow running(UUID id) {
        return new RunRow(id, "acct-1", skill.id(), skill.version(), null, json.writeValueAsString(skill), "RUNNING",
                "QUEUED", null, skill.steps().size(), null, "{\"notes\":\"Toplantı notları.\"}", null, null, false,
                null, null, Instant.EPOCH, null, null);
    }
}
