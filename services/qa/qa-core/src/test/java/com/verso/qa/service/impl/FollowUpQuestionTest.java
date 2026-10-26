package com.verso.qa.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.enums.SourceUnit;
import com.verso.platform.security.authority.CallerGroups;
import com.verso.platform.security.web.AccountId;
import com.verso.qa.api.dto.AnswerOutcome;
import com.verso.qa.api.dto.AnswerResponse;
import com.verso.qa.config.AiMode;
import com.verso.qa.config.QaFollowUpProperties;
import com.verso.qa.config.QaGroundingProperties;
import com.verso.qa.config.QaProperties;
import com.verso.qa.config.VersoAiProperties;
import com.verso.qa.service.AnswerabilityGate;
import com.verso.qa.service.CitationExtractor;
import com.verso.qa.service.ConversationHistory;
import com.verso.qa.service.ConversationHistory.PriorTurn;
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
import java.util.concurrent.atomic.AtomicInteger;
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
 * The follow-up step of {@link QuestionServiceImpl} (ADR-0046) without a container: which questions are rewritten, that
 * retrieval gets the rewritten query while the answer prompt keeps the question as asked, the stream's extra stage,
 * and every way the rewrite can fail falling back to the question as asked. HTTP and database: FollowUpApiTest.
 */
class FollowUpQuestionTest {

    /** Rewrite on, two turns, a 500 ms bound (scaled down from 10 s), the answer prompt without the context. */
    static final QaFollowUpProperties FOLLOW_UP =
            new QaFollowUpProperties(true, 2, 300, 200, 1000, 300, Duration.ofMillis(500), false);

    private static final AccountId ACCOUNT = new AccountId("acct-follow-up");
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final String REWRITTEN = "BT P2 olayında ilk yanıt süresi nedir?";

    private final DocumentRetrieval retrieval = mock(DocumentRetrieval.class);
    private final ConversationHistory history = mock(ConversationHistory.class);
    private final CallerGroups groups = mock(CallerGroups.class);
    private final ScriptedModel model = new ScriptedModel();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger serviceLog = (Logger) LoggerFactory.getLogger(QuestionServiceImpl.class);
    private ModelCircuitBreaker circuit;

    @BeforeEach
    void setUp() {
        when(groups.current()).thenReturn(List.of());
        when(retrieval.search(any(Reader.class), anyString(), anyInt())).thenReturn(List.of(new RetrievedPassage(
                UUID.randomUUID(), "bt.pdf", 1, SourceUnit.PAGE, "P2 olaylarında ilk yanıt 30 dakikadır.", 0.9, 0)));
        when(history.record(any(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of(new StoredTurn(CONVERSATION, UUID.randomUUID())));
        when(history.recent(eq(ACCOUNT), eq(CONVERSATION), anyInt())).thenReturn(List.of(
                new PriorTurn("MarkerHistory BT tarafında P1 olayında ilk yanıt süresi ne kadar?",
                        "P1 olayında ilk yanıt 15 dakikadır [1].", true)));
        appender.start();
        serviceLog.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLog.detachAppender(appender);
    }

    private QuestionServiceImpl service(QaFollowUpProperties followUp) {
        QaProperties properties = new QaProperties(5, 0.47, 0.44, 0.045, 0.50, 1500, 1, Duration.ofMillis(300), 2,
                Duration.ofSeconds(15), Duration.ofSeconds(2), Duration.ofSeconds(2));
        circuit = new ModelCircuitBreaker(properties);
        return new QuestionServiceImpl(retrieval, model, new PromptBuilder(properties), new CitationExtractor(properties),
                properties, new VersoAiProperties(AiMode.LOCAL, "test-chat", "test-embed"), circuit,
                new QaMetrics(new SimpleMeterRegistry(), circuit), history, new AnswerabilityGate(properties), groups,
                new FollowUpRewriter(followUp), followUp,
                new GroundingCheck(new QaGroundingProperties(true), properties),
                org.mockito.Mockito.mock(com.verso.qa.service.AnswerMemory.class));
    }

    @Test
    void ask_whenAFollowUpInAConversation_searchesTheRewrittenQueryAndAnswersTheQuestionAsAsked() {
        model.rewrite = REWRITTEN;

        AnswerResponse answer = service(FOLLOW_UP).ask(ACCOUNT, "Peki P2 için?", CONVERSATION);

        verify(retrieval).search(any(Reader.class), eq(REWRITTEN), anyInt());
        assertThat(answer.contextQuery()).isEqualTo(REWRITTEN);
        assertThat(answer.outcome()).isEqualTo(AnswerOutcome.ANSWERED);
        assertThat(model.rewrites.get()).isEqualTo(1);
        assertThat(model.lastRewrite).contains("MarkerHistory").contains("Son soru: Peki P2 için?");
        // The answer prompt: the question as asked, no history, no rewritten query (answer-with-context off).
        assertThat(model.lastAnswer).contains("Soru: Peki P2 için?").doesNotContain("MarkerHistory")
                .doesNotContain(REWRITTEN);
        // llm-rules 2.1: neither the history nor the rewritten query reaches a log line; the step itself does.
        assertThat(logText()).doesNotContain("MarkerHistory").doesNotContain("BT P2")
                .contains("followUp=rewritten");
    }

    @Test
    void ask_whenConfiguredToAnswerWithContext_addsOnlyTheRewrittenQueryToTheAnswerPrompt() {
        model.rewrite = REWRITTEN;
        QaFollowUpProperties withContext =
                new QaFollowUpProperties(true, 2, 300, 200, 1000, 300, Duration.ofMillis(500), true);

        service(withContext).ask(ACCOUNT, "Peki P2 için?", CONVERSATION);

        assertThat(model.lastAnswer).contains("Soru: Peki P2 için?")
                .contains("Sorunun konuşmaya göre açık hali: " + REWRITTEN).doesNotContain("MarkerHistory");
    }

    @Test
    void ask_whenTheQuestionStandsAlone_readsNoHistoryAndAsksNoRewrite() {
        AnswerResponse answer = service(FOLLOW_UP).ask(ACCOUNT, "BT tarafında P2 olayında ilk yanıt süresi ne kadar?",
                CONVERSATION);

        verify(history, never()).recent(any(), any(), anyInt());
        assertThat(model.rewrites.get()).isZero();
        assertThat(answer.contextQuery()).isNull();
        assertThat(logText()).contains("followUp=standalone");
    }

    @Test
    void ask_outsideAConversationOrWhenDisabled_neverRewrites() {
        service(FOLLOW_UP).ask(ACCOUNT, "Peki P2 için?", null);
        QaFollowUpProperties off = new QaFollowUpProperties(false, 2, 300, 200, 1000, 300, Duration.ofMillis(500), false);
        service(off).ask(ACCOUNT, "Peki P2 için?", CONVERSATION);

        verify(history, never()).recent(any(), any(), anyInt());
        assertThat(model.rewrites.get()).isZero();
        verify(retrieval, org.mockito.Mockito.times(2)).search(any(Reader.class), eq("Peki P2 için?"), anyInt());
    }

    @Test
    void ask_whenTheConversationHasNoTurnsYet_searchesAsAsked() {
        when(history.recent(eq(ACCOUNT), eq(CONVERSATION), anyInt())).thenReturn(List.of());

        AnswerResponse answer = service(FOLLOW_UP).ask(ACCOUNT, "Peki P2 için?", CONVERSATION);

        assertThat(model.rewrites.get()).isZero();
        assertThat(answer.contextQuery()).isNull();
        verify(retrieval).search(any(Reader.class), eq("Peki P2 için?"), anyInt());
    }

    /** The time bound: the rewrite is cancelled, the question is searched as asked, the slot and circuit are fine. */
    @Test
    void ask_whenTheRewriteTimesOut_searchesTheQuestionAsAskedAndStillAnswers() {
        model.rewrite = REWRITTEN;
        model.rewriteDelay = Duration.ofSeconds(3);
        QuestionServiceImpl service = service(FOLLOW_UP);

        long started = System.nanoTime();
        AnswerResponse answer = service.ask(ACCOUNT, "Peki P2 için?", CONVERSATION);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        verify(retrieval).search(any(Reader.class), eq("Peki P2 için?"), anyInt());
        assertThat(answer.contextQuery()).isNull();
        assertThat(answer.outcome()).isEqualTo(AnswerOutcome.ANSWERED);
        assertThat(circuit.isOpen()).isFalse();
        assertThat(logText()).contains("Follow-up rewrite failed: reason=TIMEOUT").contains("followUp=fallback");
    }

    @Test
    void ask_whenTheRewriteFailsOrIsNotAQuery_searchesTheQuestionAsAsked() {
        QuestionServiceImpl service = service(FOLLOW_UP);
        model.rewriteFailure = new IllegalStateException("MarkerProviderEcho");
        assertThat(service.ask(ACCOUNT, "Peki P2 için?", CONVERSATION).contextQuery()).isNull();

        model.rewriteFailure = null;
        model.rewrite = "uzun ".repeat(100);
        assertThat(service.ask(ACCOUNT, "Peki P2 için?", CONVERSATION).contextQuery()).isNull();

        model.rewrite = "peki p2 için";
        assertThat(service.ask(ACCOUNT, "Peki P2 için?", CONVERSATION).contextQuery()).as("unchanged").isNull();

        verify(retrieval, org.mockito.Mockito.times(3)).search(any(Reader.class), eq("Peki P2 için?"), anyInt());
        assertThat(circuit.isOpen()).isFalse();
        assertThat(logText()).doesNotContain("MarkerProviderEcho").contains("followUp=unchanged");
    }

    @Test
    void stream_whenAFollowUp_reportsTheRewritingStageFirstAndEndsWithTheQuery() throws Exception {
        model.rewrite = REWRITTEN;
        QuestionStreamTest.Recording out = new QuestionStreamTest.Recording(n -> true);

        service(FOLLOW_UP).stream(ACCOUNT, "Peki P2 için?", CONVERSATION, out);

        assertThat(out.await()).isEqualTo("completed");
        assertThat(out.stages).containsExactly("REWRITING", "RETRIEVING", "PASSAGES_FOUND 1/1", "WRITING");
        assertThat(out.answer.contextQuery()).isEqualTo(REWRITTEN);
        verify(retrieval).search(any(Reader.class), eq(REWRITTEN), anyInt());
    }

    @Test
    void stream_whenTheQuestionStandsAlone_hasNoRewritingStage() throws Exception {
        QuestionStreamTest.Recording out = new QuestionStreamTest.Recording(n -> true);

        service(FOLLOW_UP).stream(ACCOUNT, "BT tarafında P2 olayında ilk yanıt süresi ne kadar?", CONVERSATION, out);

        assertThat(out.await()).isEqualTo("completed");
        assertThat(out.stages).containsExactly("RETRIEVING", "PASSAGES_FOUND 1/1", "WRITING");
    }

    /** A client gone before the rewrite costs no model call and stores nothing (ADR-0035). */
    @Test
    void stream_whenTheClientLeavesAtTheRewritingStage_asksNoModel() throws Exception {
        QuestionStreamTest.Recording out = new QuestionStreamTest.Recording(n -> true);
        out.refuseStage = com.verso.qa.api.dto.AnswerStage.REWRITING;

        service(FOLLOW_UP).stream(ACCOUNT, "Peki P2 için?", CONVERSATION, out);

        assertThat(out.await()).isEqualTo("cancelled");
        assertThat(model.rewrites.get()).isZero();
        verify(retrieval, never()).search(any(Reader.class), anyString(), anyInt());
        verify(history, never()).record(any(), any(), anyString(), any(), any());
    }

    private String logText() {
        return appender.list.stream().map(e -> e.getFormattedMessage() + e.getMDCPropertyMap()
                        + (e.getArgumentArray() == null ? "" : Stream.of(e.getArgumentArray()).map(String::valueOf)
                        .collect(Collectors.joining(" "))))
                .collect(Collectors.joining("\n"));
    }

    /** Answers rewrites and answers apart (by the rewrite's system message) and keeps the last prompt of each. */
    static final class ScriptedModel implements ChatModel {
        volatile String rewrite = "";
        volatile Duration rewriteDelay = Duration.ZERO;
        volatile RuntimeException rewriteFailure;
        volatile String answer = "P2 olaylarında ilk yanıt 30 dakikadır [1].";
        volatile String lastRewrite;
        volatile String lastAnswer;
        final AtomicInteger rewrites = new AtomicInteger();
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            if (isRewrite(prompt)) {
                rewrites.incrementAndGet();
                lastRewrite = text(prompt);
                if (!rewriteDelay.isZero()) {
                    try {
                        Thread.sleep(rewriteDelay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted");
                    }
                }
                if (rewriteFailure != null) throw rewriteFailure;
                return response(rewrite);
            }
            lastAnswer = text(prompt);
            return response(answer);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            lastAnswer = text(prompt);
            return Flux.just(response(answer));
        }

        private static boolean isRewrite(Prompt prompt) {
            return prompt.getInstructions().getFirst().getText().equals(FollowUpRewriter.SYSTEM);
        }

        private static String text(Prompt prompt) {
            return prompt.getInstructions().stream().map(Message::getText).collect(Collectors.joining("\n"));
        }

        private static ChatResponse response(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }
}
