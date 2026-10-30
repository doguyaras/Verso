package com.verso.qa.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.enums.SourceUnit;
import com.verso.platform.core.exception.ServiceException;
import com.verso.platform.security.authority.CallerGroups;
import com.verso.platform.security.web.AccountId;
import com.verso.qa.api.dto.AnswerOutcome;
import com.verso.qa.api.dto.AnswerResponse;
import com.verso.qa.api.dto.AnswerStage;
import com.verso.qa.config.AiMode;
import com.verso.qa.config.QaGroundingProperties;
import com.verso.qa.config.QaProperties;
import com.verso.qa.config.VersoAiProperties;
import com.verso.qa.service.AnswerStream;
import com.verso.qa.service.AnswerabilityGate;
import com.verso.qa.service.CitationExtractor;
import com.verso.qa.service.ConversationHistory;
import com.verso.qa.service.ConversationHistory.StoredTurn;
import com.verso.qa.service.FollowUpRewriter;
import com.verso.qa.service.GroundingCheck;
import com.verso.qa.service.ModelCircuitBreaker;
import com.verso.qa.service.PromptBuilder;
import com.verso.qa.service.QaMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * The streamed answer of {@link QuestionServiceImpl} without a container or a database (ADR-0035): the real pipeline
 * pieces (gate, prompt, citations, circuit, bulkhead) with a word-counting model, a recording stream and mocked
 * retrieval and history. The HTTP and database side is QuestionStreamApiTest's.
 */
class QuestionStreamTest {

    private static final AccountId ACCOUNT = new AccountId("acct-unit");

    private final DocumentRetrieval retrieval = mock(DocumentRetrieval.class);
    private final ConversationHistory history = mock(ConversationHistory.class);
    private final CallerGroups groups = mock(CallerGroups.class);
    private final CountingModel model = new CountingModel();
    private QuestionServiceImpl service;
    private ModelCircuitBreaker circuit;

    @BeforeEach
    void setUp() {
        // One chat slot, 300 ms to wait for it, a 2 s chat bound (ADR-0008 values scaled down).
        QaProperties properties = new QaProperties(5, 0.47, 0.44, 0.045, 0.50, 1500, 1, Duration.ofMillis(300), 2,
                Duration.ofSeconds(15), Duration.ofSeconds(2), Duration.ofSeconds(2));
        circuit = new ModelCircuitBreaker(properties);
        service = new QuestionServiceImpl(retrieval, model, new PromptBuilder(properties), new CitationExtractor(properties),
                properties, new VersoAiProperties(AiMode.LOCAL, "test-chat", "test-embed"), circuit,
                new QaMetrics(new SimpleMeterRegistry(), circuit), history, new AnswerabilityGate(properties), groups,
                new FollowUpRewriter(FollowUpQuestionTest.FOLLOW_UP), FollowUpQuestionTest.FOLLOW_UP,
                new GroundingCheck(new QaGroundingProperties(true), properties),
                org.mockito.Mockito.mock(com.verso.qa.service.AnswerMemory.class),
                org.mockito.Mockito.mock(com.verso.qa.service.SkillMatcher.class),
                org.mockito.Mockito.mock(com.verso.qa.service.MultiStepReasoning.class),
                new com.verso.qa.service.AnswerRelevance(new com.verso.qa.config.QaRelevanceProperties(true)));
        when(groups.current()).thenReturn(List.of());
        when(retrieval.search(any(Reader.class), anyString(), anyInt())).thenReturn(List.of(
                new RetrievedPassage(UUID.randomUUID(), "izin.pdf", 3, SourceUnit.PAGE, "Yıllık izin yirmi gündür.", 0.9, 0)));
        when(history.record(any(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of(new StoredTurn(UUID.randomUUID(), UUID.randomUUID())));
    }

    @Test
    void stream_whenAnswered_reportsRealStagesTheTextAndTheValidatedAnswer() throws Exception {
        model.answer = "Yıllık izin yirmi gündür [1]. Ek izin vardır [8].";
        Recording out = new Recording(n -> true);

        service.stream(ACCOUNT, "Yıllık izin?", null, out);

        assertThat(out.await()).isEqualTo("completed");
        assertThat(out.stages).containsExactly("RETRIEVING", "PASSAGES_FOUND 1/1", "WRITING");
        assertThat(String.join("", out.deltas)).isEqualTo(model.answer);
        assertThat(out.answer.outcome()).isEqualTo(AnswerOutcome.ANSWERED);
        assertThat(out.answer.answer()).contains("[1]").doesNotContain("[8]");
        assertThat(out.answer.citations()).hasSize(1);
        assertThat(out.answer.turnId()).isNotNull();
    }

    @Test
    void stream_whenNoValidMarker_completesUncitedLikeTheJsonPath() throws Exception {
        model.answer = "Yirmi gün olabilir [5].";
        Recording out = new Recording(n -> true);

        service.stream(ACCOUNT, "Yıllık izin?", null, out);

        assertThat(out.await()).isEqualTo("completed");
        assertThat(out.answer.outcome()).isEqualTo(AnswerOutcome.UNCITED);
        assertThat(out.answer.found()).isFalse();
        assertThat(out.answer.citations()).isEmpty();
        assertThat(out.answer.answer()).doesNotContain("[5]");
    }

    @Test
    void stream_whenNothingIsCloseEnough_completesNotFoundWithoutAModelCall() throws Exception {
        when(retrieval.search(any(Reader.class), anyString(), anyInt())).thenReturn(List.of(
                new RetrievedPassage(UUID.randomUUID(), "x.pdf", 1, SourceUnit.PAGE, "Hava yağmurlu.", 0.1, 0)));
        Recording out = new Recording(n -> true);

        service.stream(ACCOUNT, "Yıllık izin?", null, out);

        assertThat(out.await()).isEqualTo("completed");
        assertThat(out.stages).containsExactly("RETRIEVING", "PASSAGES_FOUND 1/0");
        assertThat(out.answer.outcome()).isEqualTo(AnswerOutcome.NOT_FOUND);
        assertThat(model.calls.get()).isZero();
    }

    /** ADR-0035: the client leaving cancels the model stream, frees the slot and stores nothing. */
    @Test
    void stream_whenTheClientLeaves_cancelsTheModelFreesTheSlotAndStoresNothing() throws Exception {
        model.answer = "kelime ".repeat(40);
        model.delay = Duration.ofMillis(20);
        Recording out = new Recording(n -> n < 3);

        service.stream(ACCOUNT, "Yıllık izin?", null, out);

        assertThat(out.await()).isEqualTo("cancelled");
        assertThat(model.cancelled.get()).isTrue();
        int produced = model.emitted.get();
        Thread.sleep(200);
        assertThat(model.emitted.get()).isEqualTo(produced).isLessThan(40);
        verify(history, never()).record(any(), any(), anyString(), any(), any());
        // The one slot is free again: the next question gets to WRITING.
        model.delay = Duration.ZERO;
        model.answer = "Yirmi gün [1].";
        Recording next = new Recording(n -> true);
        service.stream(ACCOUNT, "Yıllık izin?", null, next);
        assertThat(next.await()).isEqualTo("completed");
    }

    /** A model that is still silent when the client is gone is cancelled at the next keep-alive check. */
    @Test
    void stream_whenTheClientLeavesBeforeTheFirstWord_cancelsTheSilentModel() throws Exception {
        model.answer = "Yirmi gün [1].";
        model.delay = Duration.ofSeconds(5);
        Recording out = new Recording(n -> true);
        out.alive.set(false);

        service.stream(ACCOUNT, "Yıllık izin?", null, out);

        assertThat(out.await()).isEqualTo("cancelled");
        assertThat(model.cancelled.get()).isTrue();
        assertThat(model.emitted.get()).isZero();
    }

    /** ADR-0008: the chat bound holds for a stream; the slow model is cancelled and the error code is the safe one. */
    @Test
    void stream_whenTheModelIsTooSlow_failsModelUnavailableAndCancels() throws Exception {
        model.answer = "kelime ".repeat(20);
        model.delay = Duration.ofMillis(400);
        Recording out = new Recording(n -> true);

        service.stream(ACCOUNT, "Yıllık izin?", null, out);

        assertThat(out.await()).isEqualTo("failed");
        assertThat(out.failure.getErrorCode().getCode()).isEqualTo(11001);
        assertThat(out.failure.getSafeLogReason()).isEqualTo("TIMEOUT");
        assertThat(model.cancelled.get()).isTrue();
        verify(history, never()).record(any(), any(), anyString(), any(), any());
    }

    /** The provider's message never travels: only its type becomes the reason (llm-rules 2.3). */
    @Test
    void stream_whenTheModelFails_failsWithTheTypeOnly() throws Exception {
        model.failure = new IllegalStateException("MarkerProviderEcho");
        Recording out = new Recording(n -> true);

        service.stream(ACCOUNT, "Yıllık izin?", null, out);

        assertThat(out.await()).isEqualTo("failed");
        assertThat(out.failure.getErrorCode().getCode()).isEqualTo(11001);
        assertThat(out.failure.getSafeLogReason()).isEqualTo("IllegalStateException");
        assertThat(out.failure.getMessage()).doesNotContain("MarkerProviderEcho");
    }

    /** A 200 without text is this question's failure, not an outage: twice in a row still leaves the circuit closed. */
    @Test
    void stream_whenTheModelReturnsNoText_failsWithoutOpeningTheCircuit() throws Exception {
        model.answer = "";
        for (int attempt = 0; attempt < 2; attempt++) {
            Recording out = new Recording(n -> true);

            service.stream(ACCOUNT, "Yıllık izin?", null, out);

            assertThat(out.await()).isEqualTo("failed");
            assertThat(out.failure.getErrorCode().getCode()).isEqualTo(11001);
            assertThat(out.failure.getSafeLogReason()).isEqualTo("EMPTY_ANSWER");
        }
        assertThatThrownBy(() -> service.ask(ACCOUNT, "Yıllık izin?", null))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getSafeLogReason()).isEqualTo("EMPTY_ANSWER"));
        assertThat(circuit.isOpen()).as("three empty answers are not two model failures").isFalse();
        verify(history, never()).record(any(), any(), anyString(), any(), any());
    }

    /** One bulkhead for both endpoints: a JSON answer holding the only slot makes a stream end MODEL_BUSY. */
    @Test
    void stream_whenTheJsonPathHoldsTheSlot_failsModelBusyBeforeWriting() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        model.blockCallsOn = release;
        Thread json = Thread.ofVirtual().start(() -> service.ask(ACCOUNT, "Yıllık izin?", null));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (model.calls.get() < 1) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(10);
        }
        Recording out = new Recording(n -> true);

        service.stream(ACCOUNT, "Yıllık izin?", null, out);

        assertThat(out.await()).isEqualTo("failed");
        assertThat(out.failure.getErrorCode().getCode()).isEqualTo(11002);
        assertThat(out.stages).doesNotContain("WRITING");
        release.countDown();
        json.join(5000);
    }

    /** A client gone before WRITING: the slot taken for it is given back and the model is never asked. */
    @Test
    void stream_whenTheWritingStageCannotBeWritten_freesTheSlotWithoutAModelCall() throws Exception {
        Recording out = new Recording(n -> true);
        out.refuseStage = AnswerStage.WRITING;

        service.stream(ACCOUNT, "Yıllık izin?", null, out);

        assertThat(out.await()).isEqualTo("cancelled");
        assertThat(model.calls.get()).isZero();
        Recording next = new Recording(n -> true);
        service.stream(ACCOUNT, "Yıllık izin?", null, next);
        assertThat(next.await()).as("the only slot is free again").isEqualTo("completed");
    }

    /** A provider that throws while the stream is built: a safe 11001 and the slot back. */
    @Test
    void stream_whenTheModelThrowsBeforeStreaming_failsAndFreesTheSlot() throws Exception {
        model.throwOnStream = new IllegalStateException("MarkerProviderEcho");
        Recording out = new Recording(n -> true);

        service.stream(ACCOUNT, "Yıllık izin?", null, out);

        assertThat(out.await()).isEqualTo("failed");
        assertThat(out.failure.getErrorCode().getCode()).isEqualTo(11001);
        model.throwOnStream = null;
        Recording next = new Recording(n -> true);
        service.stream(ACCOUNT, "Yıllık izin?", null, next);
        assertThat(next.await()).isEqualTo("completed");
    }

    /** A database failure during retrieval ends the stream with 99997, like the JSON path's 503. */
    @Test
    void stream_whenTheDatabaseFails_failsServiceUnavailable() throws Exception {
        when(retrieval.search(any(Reader.class), anyString(), anyInt()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        Recording out = new Recording(n -> true);

        service.stream(ACCOUNT, "Yıllık izin?", null, out);

        assertThat(out.await()).isEqualTo("failed");
        assertThat(out.failure.getErrorCode().getCode()).isEqualTo(99997);
        assertThat(model.calls.get()).isZero();
    }

    /** A model whose stream emits the answer word by word, counting words and noticing a cancel. */
    static final class CountingModel implements ChatModel {
        volatile String answer = "Yirmi gün [1].";
        volatile Duration delay = Duration.ZERO;
        volatile RuntimeException failure;
        volatile RuntimeException throwOnStream;
        volatile CountDownLatch blockCallsOn;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger emitted = new AtomicInteger();
        final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            try {
                if (blockCallsOn != null) blockCallsOn.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return response(answer);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            if (throwOnStream != null) throw throwOnStream;
            return Flux.defer(() -> {
                calls.incrementAndGet();
                if (failure != null) return Flux.<ChatResponse>error(failure);
                Flux<String> words = Flux.fromArray(answer.split("(?<= )"));
                if (!delay.isZero()) words = words.delayElements(delay);
                return words.doOnNext(w -> emitted.incrementAndGet()).doOnCancel(() -> cancelled.set(true))
                        .map(CountingModel::response);
            });
        }

        private static ChatResponse response(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }

    /** Records what the service writes; {@code accepts} decides whether the n-th delta still reaches a client. */
    static final class Recording implements AnswerStream {
        final List<String> stages = new CopyOnWriteArrayList<>();
        final List<String> deltas = new CopyOnWriteArrayList<>();
        final AtomicBoolean alive = new AtomicBoolean(true);
        volatile AnswerStage refuseStage;
        private final IntPredicate accepts;
        private final CountDownLatch end = new CountDownLatch(1);
        volatile String how;
        volatile AnswerResponse answer;
        volatile ServiceException failure;

        Recording(IntPredicate accepts) {
            this.accepts = accepts;
        }

        String await() throws InterruptedException {
            assertThat(end.await(10, TimeUnit.SECONDS)).as("the stream ends").isTrue();
            return how;
        }

        @Override
        public boolean stage(AnswerStage stage, int retrieved, int relevant) {
            if (stage == refuseStage) return false;
            stages.add(stage == AnswerStage.PASSAGES_FOUND ? stage + " " + retrieved + "/" + relevant : stage.name());
            return true;
        }

        @Override
        public boolean delta(String text) {
            if (!accepts.test(deltas.size())) return false;
            deltas.add(text);
            return true;
        }

        @Override
        public boolean keepAlive() {
            return alive.get();
        }

        @Override
        public void completed(AnswerResponse answer) {
            this.answer = answer;
            finish("completed");
        }

        @Override
        public void failed(ServiceException failure) {
            this.failure = failure;
            finish("failed");
        }

        @Override
        public void cancelled() {
            finish("cancelled");
        }

        private void finish(String how) {
            this.how = how;
            end.countDown();
        }
    }
}
