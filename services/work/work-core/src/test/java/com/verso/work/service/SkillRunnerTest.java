package com.verso.work.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.Passage;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.enums.SourceUnit;
import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.config.WorkProperties;
import com.verso.work.domain.TestSkills;
import com.verso.work.api.SkillModel;
import com.verso.work.api.SkillModelException;
import com.verso.work.outbox.WorkAudit;
import com.verso.work.repository.SkillRunRepository;
import com.verso.work.repository.SkillRunRepository.SourceRow;
import com.verso.work.repository.WorkDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The background run of ADR-0057 with a stub model and stub retrieval: the step chain, the one repair, failures as
 * fixed words, the documents filter and access re-check, the lease always released, nothing of the inputs, passages
 * or output in a log line.
 */
class SkillRunnerTest {

    private static final String VALID = "{\"sections\":{\"decisions\":{\"rows\":[{\"decision\":\"Bütçe onaylandı\","
            + "\"level\":\"Yüksek\",\"evidence\":[1]}]},\"summary\":{\"text\":\"MarkerOutput özet.\",\"evidence\":[2]}}}";

    private final DocumentRetrieval retrieval = mock(DocumentRetrieval.class);
    private final SkillRunRepository runs = mock(SkillRunRepository.class);
    private final WorkDatabase db = mock(WorkDatabase.class);
    private final WorkAudit audit = mock(WorkAudit.class);
    private final StubModel model = new StubModel();
    private final WorkProperties properties = new WorkProperties(1, Duration.ofSeconds(1), Duration.ofSeconds(5), 3,
            Duration.ofSeconds(30), 90, 500, 40, Duration.ofMillis(100), Duration.ofMinutes(1));
    private final SkillRunner runner = new SkillRunner(retrieval, model, runs, db, audit, properties,
            JsonMapper.builder().build(), new SimpleMeterRegistry());
    private final Reader reader = new Reader("acct-1", List.of("/ik"));
    private final UUID runId = UUID.randomUUID();
    private final UUID wanted = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(db.transactionally(any())).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(0)).get());
        when(runs.stage(eq(runId), anyString(), any(), any())).thenReturn(true);
        when(runs.finish(eq(runId), anyString(), anyString(), any(), anyList())).thenReturn(true);
        when(runs.fail(eq(runId), anyString(), any())).thenReturn(true);
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        when(retrieval.search(eq(reader), anyString(), anyInt())).thenReturn(List.of(
                passage(other, UUID.randomUUID(), "MarkerPassage başka belge."),
                passage(wanted, p1, "MarkerPassage bütçe onaylandı."),
                passage(wanted, p2, "MarkerPassage ikinci sayfa.")));
        when(retrieval.passage(eq(reader), any())).thenAnswer(inv -> Optional.of(new Passage(inv.getArgument(1),
                wanted, "a.pdf", 1, SourceUnit.PAGE, "metin", null, null)));
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(SkillRunner.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(SkillRunner.class)).detachAppender(logs);
    }

    @Test
    void execute_whenFirstOutputBreaksTheSchema_repairsOnceAndStoresTheDraft() {
        model.answers.addAll(List.of("notlar", "{\"sections\":{}}", VALID));
        runner.execute(job());

        assertThat(model.tasks).containsExactly("skill:test-skill:notlar", "skill:test-skill:yaz", "skill:test-skill:repair");
        verify(runs).stage(eq(runId), eq("REPAIRING"), isNull(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SourceRow>> sources = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> checks = ArgumentCaptor.forClass(String.class);
        verify(runs).finish(eq(runId), anyString(), checks.capture(), eq("stub-model"), sources.capture());
        // Only the selected document's passages (2), then the notes as one INPUT source.
        assertThat(sources.getValue()).extracting(SourceRow::kind).containsExactly("PASSAGE", "PASSAGE", "INPUT");
        assertThat(sources.getValue()).allMatch(s -> !"PASSAGE".equals(s.kind()) || wanted.equals(s.documentId()));
        assertThat(checks.getValue()).contains("\"repaired\":true").contains("\"disclaimerPresent\":true");
        verify(audit).finished(eq(runId), eq("DRAFT"), eq("stub-model"), eq(List.of(wanted)));
        assertThat(model.released).isTrue();
        assertNothingLogged();
    }

    @Test
    void execute_whenRepairAlsoFails_failsAsOutputInvalid() {
        model.answers.addAll(List.of("notlar", "değil json", "yine değil"));
        runner.execute(job());

        verify(runs).fail(runId, "OUTPUT_INVALID", "stub-model");
        verify(audit).finished(runId, "OUTPUT_INVALID", "stub-model", List.of());
        verify(runs, never()).finish(any(), any(), any(), any(), anyList());
        assertThat(model.released).isTrue();
        assertNothingLogged();
    }

    @Test
    void execute_whenTheModelTimesOut_failsWithItsWord() {
        model.failure = new SkillModelException("MODEL_TIMEOUT");
        runner.execute(job());

        verify(runs).fail(runId, "MODEL_TIMEOUT", "stub-model");
        assertThat(model.released).isTrue();
    }

    @Test
    void execute_whenRetrievalFails_failsBeforeAnyModelCall() {
        when(retrieval.search(eq(reader), anyString(), anyInt())).thenThrow(new IllegalStateException("MarkerPassage"));
        runner.execute(job());

        verify(runs).fail(runId, "RETRIEVAL_FAILED", "stub-model");
        assertThat(model.tasks).isEmpty();
        assertNothingLogged();
    }

    @Test
    void execute_whenTheRunWasDeletedMeanwhile_stopsWithoutCallingTheModel() {
        when(runs.stage(eq(runId), anyString(), any(), any())).thenReturn(false);
        runner.execute(job());

        assertThat(model.tasks).isEmpty();
        verify(runs, never()).fail(any(), any(), any());
        assertThat(model.released).isTrue();
    }

    @Test
    void execute_whenACitedPassageIsNoLongerReadable_countsIt() {
        when(retrieval.passage(eq(reader), any())).thenReturn(Optional.empty());
        model.answers.addAll(List.of("notlar", VALID));
        runner.execute(job());

        ArgumentCaptor<String> checks = ArgumentCaptor.forClass(String.class);
        verify(runs).finish(eq(runId), anyString(), checks.capture(), any(), anyList());
        assertThat(checks.getValue()).contains("\"unreadableCitation\":2");
    }

    private SkillRunner.Job job() {
        SkillDefinition skill = TestSkills.valid();
        Map<String, Object> values = Map.of("notes", "MarkerInput karar alındı.", "docs", List.of(wanted.toString()));
        return new SkillRunner.Job(runId, skill, values, reader, model.tryAcquire(Duration.ZERO).orElseThrow());
    }

    private void assertNothingLogged() {
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(line -> line.contains("Marker") || line.contains("bütçe"));
    }

    private static RetrievedPassage passage(UUID document, UUID passage, String text) {
        return new RetrievedPassage(document, "a.pdf", 1, SourceUnit.PAGE, text, 0.8, 1.0, null, null, passage);
    }

    /** Answers in order, or fails; records the task words and whether the lease was released. */
    private static final class StubModel implements SkillModel {
        final List<String> answers = new ArrayList<>();
        final List<String> tasks = new ArrayList<>();
        final AtomicBoolean released = new AtomicBoolean();
        SkillModelException failure;

        @Override
        public Optional<Lease> tryAcquire(Duration wait) {
            return Optional.of(() -> released.set(true));
        }

        @Override
        public String complete(Call call) {
            if (failure != null) throw failure;
            tasks.add(call.task());
            return answers.removeFirst();
        }

        @Override
        public String modelId() {
            return "stub-model";
        }
    }
}
