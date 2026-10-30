package com.verso.qa.service.impl;

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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.DocumentSystemModel;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.enums.SourceUnit;
import com.verso.platform.core.exception.CommonErrorCode;
import com.verso.platform.core.exception.ServiceException;
import com.verso.platform.security.authority.CallerGroups;
import com.verso.platform.security.web.AccountId;
import com.verso.qa.api.dto.AnswerOutcome;
import com.verso.qa.api.dto.AnswerResponse;
import com.verso.qa.api.dto.AnswerStage;
import com.verso.qa.api.dto.ReasoningTrace;
import com.verso.qa.api.dto.ReasoningTrace.ContradictionNote;
import com.verso.qa.api.dto.ReasoningTrace.ContradictionSource;
import com.verso.qa.config.AiMode;
import com.verso.qa.config.QaFollowUpProperties;
import com.verso.qa.config.QaGroundingProperties;
import com.verso.qa.config.QaProperties;
import com.verso.qa.config.QaReasoningProperties;
import com.verso.qa.config.VersoAiProperties;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import com.verso.qa.repository.ContradictionRepository;
import com.verso.qa.repository.ContradictionRepository.Candidate;
import com.verso.qa.repository.ContradictionRepository.Side;
import com.verso.qa.service.AnswerStream;
import com.verso.qa.service.AnswerabilityGate;
import com.verso.qa.service.CitationExtractor;
import com.verso.qa.service.ConversationHistory;
import com.verso.qa.service.ConversationHistory.StoredTurn;
import com.verso.qa.service.FollowUpRewriter;
import com.verso.qa.service.GroundingCheck;
import com.verso.qa.service.ModelCircuitBreaker;
import com.verso.qa.service.MultiStepReasoning;
import com.verso.qa.service.PromptBuilder;
import com.verso.qa.service.QaMetrics;
import com.verso.qa.service.QuestionDecomposer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Multi-step reasoning in {@link QuestionServiceImpl} (ADR-0055) without a container: the flag off changes nothing,
 * a comparison costs one decomposition and one synthesis call (two, not N + 1), sub-questions are searched with the caller's
 * reader, citations are validated on the merged list, a sub-question without passages is stated explicitly, every
 * fallback answers in a single pass, the stream reports the new stages, contradiction notes come only from the stored
 * scan, and no sub-question reaches a log line. HTTP and database: MultiStepApiTest.
 */
class MultiStepQuestionTest {

    private static final AccountId ACCOUNT = new AccountId("acct-multi-step");
    private static final String QUESTION = "P1 olayında BT ile müşteri destek SLA'ları arasındaki fark nedir?";
    private static final String SUB_BT = "MarkerSub BT P1 olayında ilk yanıt süresi nedir?";
    private static final String SUB_SUPPORT = "MarkerSub müşteri destek P1 ilk yanıt süresi nedir?";
    private static final String DECOMPOSITION = "1. " + SUB_BT + "\n2. " + SUB_SUPPORT;
    private static final QaFollowUpProperties FOLLOW_UP =
            new QaFollowUpProperties(true, 2, 300, 200, 1000, 300, Duration.ofMillis(500), false);

    private final DocumentRetrieval retrieval = mock(DocumentRetrieval.class);
    private final DocumentSystemModel systemModel = mock(DocumentSystemModel.class);
    private final ContradictionRepository contradictions = mock(ContradictionRepository.class);
    private final ConversationHistory history = mock(ConversationHistory.class);
    private final CallerGroups groups = mock(CallerGroups.class);
    private final Model model = new Model();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger serviceLog = (Logger) LoggerFactory.getLogger("com.verso.qa");
    private final RetrievedPassage whole = passage("ozet.pdf", 1, "Genel SLA özeti", 0.9);
    private final RetrievedPassage bt = passage("bt.pdf", 1, "BT P1 ilk yanıt 15 dakikadır.", 0.9);
    private final RetrievedPassage support = passage("destek.pdf", 2, "Destek P1 ilk yanıt 1 saattir.", 0.9);
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        when(groups.current()).thenReturn(List.of("grp-ops"));
        when(retrieval.search(any(Reader.class), eq(QUESTION), anyInt())).thenReturn(List.of(whole));
        when(retrieval.search(any(Reader.class), eq(SUB_BT), anyInt())).thenReturn(List.of(bt));
        when(retrieval.search(any(Reader.class), eq(SUB_SUPPORT), anyInt())).thenReturn(List.of(support));
        when(history.record(any(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of(new StoredTurn(UUID.randomUUID(), UUID.randomUUID())));
        appender.start();
        serviceLog.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLog.detachAppender(appender);
    }

    private QuestionServiceImpl service(boolean enabled) {
        return service(new QaReasoningProperties(enabled, 3, 200, 3, 6, true, Duration.ofMillis(400),
                Duration.ofSeconds(5)));
    }

    private QuestionServiceImpl service(QaReasoningProperties reasoning) {
        QaProperties properties = new QaProperties(5, 0.47, 0.44, 0.045, 0.50, 1500, 1, Duration.ofMillis(200), 2,
                Duration.ofSeconds(15), Duration.ofSeconds(2), Duration.ofSeconds(2));
        ModelCircuitBreaker circuit = new ModelCircuitBreaker(properties);
        registry = new SimpleMeterRegistry();
        AnswerabilityGate gate = new AnswerabilityGate(properties);
        return new QuestionServiceImpl(retrieval, model, new PromptBuilder(properties), new CitationExtractor(properties),
                properties, new VersoAiProperties(AiMode.LOCAL, "test-chat", "test-embed"), circuit,
                new QaMetrics(registry, circuit), history, gate, groups, new FollowUpRewriter(FOLLOW_UP), FOLLOW_UP,
                new GroundingCheck(new QaGroundingProperties(true), properties),
                mock(com.verso.qa.service.AnswerMemory.class), mock(com.verso.qa.service.SkillMatcher.class),
                new MultiStepReasoning(reasoning, properties, new QuestionDecomposer(reasoning), retrieval,
                        systemModel, gate, contradictions),
                new com.verso.qa.service.AnswerRelevance(new com.verso.qa.config.QaRelevanceProperties(true)));
    }

    @Test
    void ask_whenTheFlagIsOff_answersInOnePassWithoutDecomposing() {
        model.decomposition = DECOMPOSITION;

        AnswerResponse answer = service(false).ask(ACCOUNT, QUESTION, null);

        assertThat(model.decompositions).isEmpty();
        assertThat(model.answers).hasSize(1);
        assertThat(answer.reasoning()).isNull();
        verify(retrieval, never()).search(any(Reader.class), eq(SUB_BT), anyInt());
        verify(systemModel, never()).lookup(any(), anyString(), anyInt());
        assertThat(model.answers.getFirst()).doesNotContain("Alt soru");
    }

    @Test
    void ask_whenAComparison_decomposesOnceSearchesEachSubQuestionAndSynthesisesOnce() {
        model.decomposition = DECOMPOSITION;
        model.answer = "BT'de ilk yanıt 15 dakikadır [1]; destekte 1 saattir [2]. Fark 45 dakikadır [9].";

        AnswerResponse answer = service(true).ask(ACCOUNT, QUESTION, null);

        // Two chat calls whatever N: one decomposition, one synthesis, no answer per sub-question.
        assertThat(model.decompositions).hasSize(1);
        assertThat(model.answers).hasSize(1);
        Reader reader = new Reader(ACCOUNT.value(), List.of("grp-ops"));
        verify(retrieval).search(eq(reader), eq(SUB_BT), eq(5));
        verify(retrieval).search(eq(reader), eq(SUB_SUPPORT), eq(5));
        String prompt = model.answers.getFirst();
        assertThat(prompt).contains("Alt soru 1: " + SUB_BT).contains("Alt soru 2: " + SUB_SUPPORT)
                .contains("[[BELGE 1]] (sayfa 1)\nBT P1").contains("[[BELGE 2]] (sayfa 2)\nDestek P1")
                .contains("Soru: " + QUESTION).doesNotContain("Genel SLA özeti");
        // Citations validated on the merged list: [9] does not exist and is dropped.
        assertThat(answer.citations()).extracting(c -> c.fileName() + "#" + c.page())
                .containsExactly("bt.pdf#1", "destek.pdf#2");
        assertThat(answer.answer()).doesNotContain("[9]");
        assertThat(answer.outcome()).isEqualTo(AnswerOutcome.ANSWERED);
        ReasoningTrace trace = answer.reasoning();
        assertThat(trace.steps()).extracting(ReasoningTrace.Step::question).containsExactly(SUB_BT, SUB_SUPPORT);
        assertThat(trace.steps()).extracting(ReasoningTrace.Step::found).containsExactly(true, true);
        assertThat(trace.steps()).extracting(ReasoningTrace.Step::citations)
                .containsExactly(List.of(1), List.of(2));
        assertThat(trace.contradictions()).isEmpty();
        assertThat(counter("decomposed")).isEqualTo(1);
        assertNoSubQuestionLogged();
        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("reasoning=multi_step steps=2"));
    }

    @Test
    void ask_whenASubQuestionFindsNothing_saysSoExplicitlyInsteadOfGuessing() {
        model.decomposition = DECOMPOSITION;
        model.answer = "BT'de ilk yanıt 15 dakikadır [1].";
        when(retrieval.search(any(Reader.class), eq(SUB_SUPPORT), anyInt()))
                .thenReturn(List.of(passage("x.pdf", 1, "ilgisiz", 0.1)));

        AnswerResponse answer = service(true).ask(ACCOUNT, QUESTION, null);

        assertThat(answer.answer()).endsWith(
                "\n\n“MarkerSub müşteri destek P1 ilk yanıt süresi nedir” için belgelerde bilgi bulunamadı.");
        assertThat(answer.reasoning().steps()).extracting(ReasoningTrace.Step::found).containsExactly(true, false);
        assertThat(model.answers.getFirst()).contains("Alt soru 2: " + SUB_SUPPORT + "\nBilgi bulunamadı.")
                .doesNotContain("ilgisiz");
        assertThat(answer.outcome()).isEqualTo(AnswerOutcome.ANSWERED);
    }

    @Test
    void ask_whenNoSubQuestionNorTheQuestionFindsAnything_isNotFoundWithoutASynthesisCall() {
        model.decomposition = DECOMPOSITION;
        RetrievedPassage nothing = passage("x.pdf", 1, "ilgisiz", 0.1);
        when(retrieval.search(any(Reader.class), anyString(), anyInt())).thenReturn(List.of(nothing));

        AnswerResponse answer = service(true).ask(ACCOUNT, QUESTION, null);

        assertThat(answer.outcome()).isEqualTo(AnswerOutcome.NOT_FOUND);
        assertThat(answer.answer()).isEqualTo(PromptBuilder.NOT_FOUND);
        assertThat(answer.reasoning().steps()).extracting(ReasoningTrace.Step::found).containsExactly(false, false);
        assertThat(model.answers).isEmpty();
    }

    @Test
    void ask_whenSubQuestionsFindNothingButTheQuestionDoes_fallsBackToTheSinglePass() {
        model.decomposition = DECOMPOSITION;
        RetrievedPassage nothing = passage("x.pdf", 1, "ilgisiz", 0.1);
        when(retrieval.search(any(Reader.class), eq(SUB_BT), anyInt())).thenReturn(List.of(nothing));
        when(retrieval.search(any(Reader.class), eq(SUB_SUPPORT), anyInt())).thenReturn(List.of(nothing));

        AnswerResponse answer = service(true).ask(ACCOUNT, QUESTION, null);

        assertSinglePass(answer);
        assertThat(counter("fallback_no_passages")).isEqualTo(1);
    }

    @Test
    void ask_whenTheDecompositionIsInvalid_fallsBackToTheSinglePass() {
        model.decomposition = "Bu soruyu ikiye bölüyorum:\n1. " + SUB_BT;

        AnswerResponse answer = service(true).ask(ACCOUNT, QUESTION, null);

        assertSinglePass(answer);
        assertThat(counter("fallback_invalid")).isEqualTo(1);
    }

    @Test
    void ask_whenTheDecompositionTimesOut_fallsBackToTheSinglePass() {
        model.decomposition = DECOMPOSITION;
        model.decompositionDelay = Duration.ofSeconds(2);

        long started = System.nanoTime();
        AnswerResponse answer = service(true).ask(ACCOUNT, QUESTION, null);

        assertSinglePass(answer);
        assertThat(counter("fallback_timeout")).isEqualTo(1);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1900));
    }

    @Test
    void ask_whenTheDecompositionFails_fallsBackToTheSinglePassAndLeavesTheCircuitAlone() {
        model.decompositionFailure = new IllegalStateException("provider said MarkerSub");

        AnswerResponse answer = service(true).ask(ACCOUNT, QUESTION, null);

        assertSinglePass(answer);
        assertThat(counter("fallback_failed")).isEqualTo(1);
        assertNoSubQuestionLogged();
    }

    @Test
    void ask_whenASubQuestionSearchFails_fallsBackToTheSinglePass() {
        model.decomposition = DECOMPOSITION;
        when(retrieval.search(any(Reader.class), eq(SUB_SUPPORT), anyInt()))
                .thenThrow(new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE, "TIMEOUT"));

        AnswerResponse answer = service(true).ask(ACCOUNT, QUESTION, null);

        assertSinglePass(answer);
        assertThat(counter("fallback_retrieval")).isEqualTo(1);
    }

    @Test
    void ask_whenTheChatSlotIsBusy_fallsBackWithoutCountingABusyQuestion() throws Exception {
        model.decomposition = DECOMPOSITION;
        QuestionServiceImpl service = service(true);
        Semaphore slots = slots(service);
        slots.acquire();
        try {
            // The decomposition gives up its turn; the answer call then gets the usual 503 MODEL_BUSY.
            assertThatThrownBy(() -> service.ask(ACCOUNT, QUESTION, null)).isInstanceOf(QaServiceException.class)
                    .extracting(e -> ((QaServiceException) e).getErrorCode()).isEqualTo(QaErrorCode.MODEL_BUSY);
        } finally {
            slots.release();
        }
        assertThat(counter("fallback_busy")).isEqualTo(1);
        assertThat(model.decompositions).isEmpty();
        assertThat(registry.get("verso.qa.questions").tag("outcome", "model_busy").counter().count()).isEqualTo(1);
    }

    @Test
    void ask_whenTheGateDoesNotFire_asksNoDecomposition() {
        model.decomposition = DECOMPOSITION;
        when(retrieval.search(any(Reader.class), eq("BT P1 olayında ilk yanıt süresi kaç dakikadır?"), anyInt()))
                .thenReturn(List.of(bt));

        AnswerResponse answer = service(true).ask(ACCOUNT, "BT P1 olayında ilk yanıt süresi kaç dakikadır?", null);

        assertThat(model.decompositions).isEmpty();
        assertThat(answer.reasoning()).isNull();
        assertThat(counter("not_triggered")).isEqualTo(1);
    }

    @Test
    void ask_whenTheScanStoredAContradictionBetweenCitedPassages_notesItAndTheModelNeverDecides() throws Exception {
        model.decomposition = DECOMPOSITION;
        // The model claims a contradiction: that is ignored; only the stored candidate counts.
        model.answer = "BT'de 15 dakika [1], destekte 1 saat [2]. Belgeler arasında çelişki var.";
        when(contradictions.between(any())).thenReturn(List.of(new Candidate(UUID.randomUUID(),
                new Side(bt.passageId(), bt.documentId(), 1, sha256(bt.content())),
                new Side(support.passageId(), support.documentId(), 2, sha256(support.content())), 0.97, 1,
                Instant.now(), Instant.now())));

        AnswerResponse answer = service(true).ask(ACCOUNT, QUESTION, null);

        assertThat(answer.reasoning().contradictions())
                .containsExactly(new ContradictionNote(1, 2, ContradictionSource.CONTRADICTION_SCAN));

        when(contradictions.between(any())).thenReturn(List.of());
        assertThat(service(true).ask(ACCOUNT, QUESTION, null).reasoning().contradictions()).isEmpty();
    }

    @Test
    void stream_whenAComparison_reportsDecomposingEachSubQuestionAndTheMergedPassages() throws Exception {
        model.decomposition = DECOMPOSITION;
        model.answer = "BT'de 15 dakika [1], destekte 1 saat [2].";
        Stages out = new Stages();

        service(true).stream(ACCOUNT, QUESTION, null, out);

        assertThat(out.end.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(out.stages).containsExactly("RETRIEVING", "DECOMPOSING", "SUB_QUESTION 1/2", "SUB_QUESTION 2/2",
                "PASSAGES_FOUND 2/2", "WRITING");
        assertThat(out.answer.reasoning().steps()).hasSize(2);
        assertThat(out.answer.citations()).hasSize(2);
        assertNoSubQuestionLogged();
    }

    @Test
    void stream_whenTheDecompositionFallsBack_reportsTheSinglePassStages() throws Exception {
        model.decomposition = "geçersiz";
        Stages out = new Stages();

        service(true).stream(ACCOUNT, QUESTION, null, out);

        assertThat(out.end.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(out.stages).containsExactly("RETRIEVING", "DECOMPOSING", "PASSAGES_FOUND 1/1", "WRITING");
        assertThat(out.answer.reasoning()).isNull();
    }

    private void assertSinglePass(AnswerResponse answer) {
        assertThat(answer.reasoning()).isNull();
        assertThat(model.answers).hasSize(1);
        assertThat(model.answers.getFirst()).doesNotContain("Alt soru").contains("Genel SLA özeti");
        assertThat(answer.citations()).extracting(c -> c.fileName()).containsExactly("ozet.pdf");
    }

    private double counter(String result) {
        return registry.get("verso.qa.reasoning").tag("result", result).counter().count();
    }

    /** llm-rules 2.1: the sub-questions are content; only counts and fixed words reach a log line. */
    private void assertNoSubQuestionLogged() {
        for (ILoggingEvent event : appender.list) {
            String text = event.getFormattedMessage() + " " + (event.getArgumentArray() == null ? ""
                    : Stream.of(event.getArgumentArray()).map(String::valueOf).collect(Collectors.joining(" ")));
            assertThat(text).doesNotContain("MarkerSub").doesNotContain("SLA").doesNotContain("15 dakika");
        }
    }

    private static Semaphore slots(QuestionServiceImpl service) throws ReflectiveOperationException {
        Field field = QuestionServiceImpl.class.getDeclaredField("chatSlots");
        field.setAccessible(true);
        return (Semaphore) field.get(service);
    }

    private static RetrievedPassage passage(String file, int page, String content, double similarity) {
        return new RetrievedPassage(UUID.nameUUIDFromBytes(file.getBytes(StandardCharsets.UTF_8)), file, page,
                SourceUnit.PAGE, content, similarity, 1.0, null, null, UUID.randomUUID());
    }

    private static String sha256(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    /** Tells a decomposition (by its system message) from an answer; records both prompts. */
    static final class Model implements ChatModel {
        volatile String decomposition = "";
        volatile Duration decompositionDelay = Duration.ZERO;
        volatile RuntimeException decompositionFailure;
        volatile String answer = "Genel SLA özeti [1].";
        final List<String> decompositions = new CopyOnWriteArrayList<>();
        final List<String> answers = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            if (prompt.getInstructions().getFirst().getText().equals(QuestionDecomposer.SYSTEM)) {
                decompositions.add(text(prompt));
                if (!decompositionDelay.isZero()) {
                    try {
                        Thread.sleep(decompositionDelay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted");
                    }
                }
                if (decompositionFailure != null) throw decompositionFailure;
                return response(decomposition);
            }
            answers.add(text(prompt));
            return response(answer);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            answers.add(text(prompt));
            return Flux.just(response(answer));
        }

        private static String text(Prompt prompt) {
            return prompt.getInstructions().stream().map(Message::getText).collect(Collectors.joining("\n"));
        }

        private static ChatResponse response(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }

    /** Records the stages, with the sub-question position and the merged counts. */
    static final class Stages implements AnswerStream {
        final List<String> stages = new CopyOnWriteArrayList<>();
        final CountDownLatch end = new CountDownLatch(1);
        volatile AnswerResponse answer;

        @Override
        public boolean stage(AnswerStage stage, int retrieved, int relevant) {
            stages.add(stage == AnswerStage.PASSAGES_FOUND ? stage + " " + retrieved + "/" + relevant : stage.name());
            return true;
        }

        @Override
        public boolean subQuestion(int step, int steps) {
            stages.add("SUB_QUESTION " + step + "/" + steps);
            return true;
        }

        @Override
        public boolean delta(String text) {
            return true;
        }

        @Override
        public boolean keepAlive() {
            return true;
        }

        @Override
        public void completed(AnswerResponse answer) {
            this.answer = answer;
            end.countDown();
        }

        @Override
        public void failed(ServiceException failure) {
            end.countDown();
        }

        @Override
        public void cancelled() {
            end.countDown();
        }
    }
}
