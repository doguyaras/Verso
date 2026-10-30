package com.verso.qa.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.enums.SourceUnit;
import com.verso.platform.core.exception.ErrorCode;
import com.verso.platform.core.exception.ServiceException;
import com.verso.platform.security.authority.CallerGroups;
import com.verso.platform.security.web.AccountId;
import com.verso.qa.config.AiMode;
import com.verso.qa.config.QaGroundingProperties;
import com.verso.qa.config.QaProperties;
import com.verso.qa.config.VersoAiProperties;
import com.verso.qa.service.AnswerMemory;
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
import com.verso.qa.service.SkillMatcher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;

/**
 * ADR-0062: a stuck chat runner costs one question, not the service. The answer call is given up at its bound and
 * interrupted (which cancels the HTTP request), its slot comes back, the stall is counted; the chat circuit counts chat
 * failures only, so failing embeddings never open it and a stuck chat never stops the question embedding. A stream
 * whose model sends nothing is cut at the first-token bound, before the whole chat timeout.
 */
class ChatStallTest {

    private static final AccountId ACCOUNT = new AccountId("acct-stall");
    /** The embedding model's 503 (document module, 10030), as DocumentRetrieval throws it. */
    private static final ErrorCode EMBEDDING_UNAVAILABLE = new ErrorCode() {
        @Override
        public int getCode() {
            return 10030;
        }

        @Override
        public String getMessage() {
            return "The embedding model is unavailable, try again later.";
        }

        @Override
        public String getService() {
            return "document";
        }

        @Override
        public HttpStatus getHttpStatus() {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
    };

    private final DocumentRetrieval retrieval = mock(DocumentRetrieval.class);
    private final ConversationHistory history = mock(ConversationHistory.class);
    private final CallerGroups groups = mock(CallerGroups.class);
    private final StuckModel model = new StuckModel();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private ModelCircuitBreaker circuit;
    private QuestionServiceImpl service;

    @BeforeEach
    void setUp() {
        // One chat slot, 300 ms to wait for it, two failures open the circuit; a 1 s chat bound and a 300 ms
        // first-token bound (ADR-0008 and ADR-0062 values scaled down).
        QaProperties properties = new QaProperties(5, 0.47, 0.44, 0.045, 0.50, 1500, 1, Duration.ofMillis(300), 2,
                Duration.ofSeconds(15), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(300));
        circuit = new ModelCircuitBreaker(properties);
        service = new QuestionServiceImpl(retrieval, model, new PromptBuilder(properties), new CitationExtractor(properties),
                properties, new VersoAiProperties(AiMode.LOCAL, "test-chat", "test-embed"), circuit,
                new QaMetrics(registry, circuit), history, new AnswerabilityGate(properties), groups,
                new FollowUpRewriter(FollowUpQuestionTest.FOLLOW_UP), FollowUpQuestionTest.FOLLOW_UP,
                new GroundingCheck(new QaGroundingProperties(true), properties), mock(AnswerMemory.class),
                mock(SkillMatcher.class), mock(MultiStepReasoning.class));
        when(groups.current()).thenReturn(List.of());
        when(retrieval.search(any(Reader.class), anyString(), anyInt())).thenReturn(List.of(
                new RetrievedPassage(UUID.randomUUID(), "izin.pdf", 3, SourceUnit.PAGE, "Yıllık izin yirmi gündür.", 0.9, 0)));
        when(history.record(any(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of(new StoredTurn(UUID.randomUUID(), UUID.randomUUID())));
    }

    @Test
    void ask_whenTheChatModelHangs_interruptsTheCallFreesTheSlotAndCountsTheStall() throws Exception {
        model.stuck = true;

        ServiceException failure = catchThrowableOfType(ServiceException.class,
                () -> service.ask(ACCOUNT, "Yıllık izin?", null));

        assertThat(failure.getErrorCode().getCode()).as("the user-facing error stays MODEL_UNAVAILABLE").isEqualTo(11001);
        assertThat(failure.getSafeLogReason()).isEqualTo("TIMEOUT");
        assertThat(model.interrupted.await(5, TimeUnit.SECONDS)).as("the provider call is interrupted (cancelled)")
                .isTrue();
        assertThat(stalls("timeout")).isEqualTo(1);
        assertThat(stalls("first_token")).isZero();

        // The slot came back with the cancelled call: the next question reaches the model, not MODEL_BUSY.
        model.stuck = false;
        assertThat(service.ask(ACCOUNT, "Yıllık izin?", null).answer()).contains("[1]");
        assertThat(model.calls.get()).isEqualTo(2);
    }

    @Test
    void ask_whileTheChatModelHangs_stillEmbedsAndSearchesTheNextQuestion() throws Exception {
        model.stuck = true;
        Thread first = Thread.ofVirtual().start(() -> catchThrowableOfType(ServiceException.class,
                () -> service.ask(ACCOUNT, "Yıllık izin?", null)));
        assertThat(model.entered.await(5, TimeUnit.SECONDS)).isTrue();

        ServiceException busy = catchThrowableOfType(ServiceException.class,
                () -> service.ask(ACCOUNT, "Fazla mesai?", null));

        // The stuck chat holds its own bulkhead only: the second question was embedded and searched, then refused
        // for the chat slot (MODEL_BUSY), not for the embedding.
        assertThat(busy.getErrorCode().getCode()).isEqualTo(11002);
        verify(retrieval, times(2)).search(any(Reader.class), anyString(), anyInt());
        first.join(Duration.ofSeconds(5));
    }

    @Test
    void ask_whenEmbeddingsFail_neverOpensTheChatCircuit() {
        when(retrieval.search(any(Reader.class), anyString(), anyInt()))
                .thenThrow(new ServiceException(EMBEDDING_UNAVAILABLE, "TIMEOUT"));

        for (int i = 0; i < 3; i++) {
            ServiceException failure = catchThrowableOfType(ServiceException.class,
                    () -> service.ask(ACCOUNT, "Yıllık izin?", null));
            assertThat(failure.getErrorCode().getCode()).isEqualTo(10030);
        }

        assertThat(circuit.isOpen()).as("embedding failures are not chat failures").isFalse();
        assertThat(model.calls.get()).isZero();
        assertThat(stalls("timeout")).isZero();
    }

    @Test
    void stream_whenTheModelSendsNoToken_givesUpAtTheFirstTokenBoundAndCancels() throws Exception {
        model.silent = true;
        QuestionStreamTest.Recording out = new QuestionStreamTest.Recording(n -> true);
        long started = System.nanoTime();

        service.stream(ACCOUNT, "Yıllık izin?", null, out);

        assertThat(out.await()).isEqualTo("failed");
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertThat(out.failure.getErrorCode().getCode()).isEqualTo(11001);
        assertThat(out.failure.getSafeLogReason()).isEqualTo("FIRST_TOKEN_TIMEOUT");
        assertThat(took).as("cut at the 300 ms first-token bound, before the 1 s chat bound")
                .isLessThan(Duration.ofMillis(950));
        assertThat(model.streamCancelled.get()).as("the provider stream is cancelled").isTrue();
        assertThat(stalls("first_token")).isEqualTo(1);
    }

    @Test
    void properties_whenTheFirstTokenBoundIsNotPositive_refuseToStart() {
        for (Duration bound : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1)}) {
            IllegalStateException refused = catchThrowableOfType(IllegalStateException.class, () -> new QaProperties(5,
                    0.47, 0.44, 0.045, 0.50, 1500, 1, Duration.ofSeconds(5), 2, Duration.ofSeconds(15),
                    Duration.ofSeconds(90), Duration.ofSeconds(30), bound));
            assertThat(refused).hasMessageContaining("verso.qa.first-token-timeout");
        }
        // Without its own bound the chat timeout alone applies: the older constructor never cuts a stream sooner.
        assertThat(new QaProperties(5, 0.47, 0.44, 0.045, 0.50, 1500, 1, Duration.ofSeconds(5), 2,
                Duration.ofSeconds(15), Duration.ofSeconds(90), Duration.ofSeconds(30)).firstTokenTimeout())
                .isEqualTo(Duration.ofSeconds(90));
    }

    private double stalls(String phase) {
        return registry.get("verso.qa.chat.stalls").tag("phase", phase).counter().count();
    }

    /** A runner that stops answering: call() blocks until interrupted; a silent stream never emits. */
    static final class StuckModel implements ChatModel {
        volatile boolean stuck;
        volatile boolean silent;
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final AtomicBoolean streamCancelled = new AtomicBoolean();

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            entered.countDown();
            if (stuck) {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    throw new IllegalStateException("cancelled");
                }
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("Yirmi gün [1]."))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            calls.incrementAndGet();
            if (silent) return Flux.<ChatResponse>never().doOnCancel(() -> streamCancelled.set(true));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("Yirmi gün [1].")))));
        }
    }
}
