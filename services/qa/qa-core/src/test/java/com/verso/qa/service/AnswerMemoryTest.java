package com.verso.qa.service;

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
import com.verso.document.api.DocumentProvenance;
import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.QuestionEmbedding;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SourcePage;
import com.verso.document.api.enums.SourceUnit;
import com.verso.qa.api.dto.KnowledgeCardState;
import com.verso.qa.config.QaKnowledgeProperties;
import com.verso.qa.config.QaKnowledgeProperties.RerankCheck;
import com.verso.qa.config.QaProperties;
import com.verso.qa.repository.KnowledgeCardRepository;
import com.verso.qa.repository.KnowledgeCardRepository.Binding;
import com.verso.qa.repository.KnowledgeCardRepository.Candidate;
import com.verso.qa.repository.KnowledgeCardRepository.CardRow;
import com.verso.qa.repository.KnowledgeCardRepository.CitationRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;

/**
 * The serving rules of the answer memory (ADR-0049) with stubbed repositories: a card answers only when it matches
 * above the threshold, is VERIFIED and unexpired, and every bound chunk is visible to this reader with its hash.
 * ADR-0060: and only when the key facts agree (and, with the rerank check on, the reranker confirms); a rejection is
 * counted and logged by its reason code, never with the question.
 */
class AnswerMemoryTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final UUID CARD = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();
    private static final UUID COLLECTION = UUID.randomUUID();
    private static final String HASH = "d".repeat(64);
    private static final Reader READER = new Reader("acct-reader", List.of("/hukuk"));
    private static final QuestionEmbedding EMBEDDING = new QuestionEmbedding("bge-m3", new float[] {1f, 0f});
    private static final String QUESTION = "Yıllık izin kaç gün?";
    // A synthetic marker: if it ever reaches a log line, the log-privacy assertion fails (llm-rules 2.1).
    private static final String NEAR_MISS = "Yıllık izin 5 yıldan sonra kaç gün olur QZXMARKER?";

    private final KnowledgeCardRepository cards = mock(KnowledgeCardRepository.class);
    private final DocumentProvenance provenance = mock(DocumentProvenance.class);
    private final DocumentRetrieval retrieval = mock(DocumentRetrieval.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final QaProperties qa = new QaProperties(5, 0.47, 0.44, 0.045, 0.50, 1500, 1, Duration.ofSeconds(5), 2,
            Duration.ofSeconds(15), Duration.ofSeconds(90), Duration.ofSeconds(30));
    private final AnswerMemory memory = memory(RerankCheck.OFF);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger memoryLog = (Logger) LoggerFactory.getLogger(AnswerMemory.class);

    @BeforeEach
    void attachLog() {
        appender.start();
        memoryLog.addAppender(appender);
    }

    @AfterEach
    void detachLog() {
        memoryLog.detachAppender(appender);
    }

    private AnswerMemory memory(RerankCheck check) {
        return new AnswerMemory(cards, provenance, retrieval, new CitationExtractor(qa),
                new QaKnowledgeProperties(true, 0.93, 3, Duration.ofDays(90), false, 3, Duration.ofDays(365),
                        KnowledgeCardRulesTest.eval(), check), Clock.fixed(NOW, ZoneOffset.UTC), registry);
    }

    @Test
    void lookup_whenTheQuestionStatesAKeyFactTheCardDoesNot_rejectsTheCardBeforeReadingAnySource() {
        stubCard(KnowledgeCardState.VERIFIED, NOW.plusSeconds(3600), 0.99);
        when(provenance.sourceChunks(eq(READER), any())).thenReturn(List.of(visible(HASH)));

        assertThat(memory.lookup(READER, EMBEDDING, NEAR_MISS)).isEmpty();

        verify(provenance, never()).sourceChunks(any(), any());
        verify(cards, never()).bindings(any());
        assertThat(rejections("key_fact_quantity")).isEqualTo(1);
        assertThat(lookups("rejected")).isEqualTo(1);
        assertThat(lookups("not_readable")).isZero();
        String logs = logs();
        assertThat(logs).contains("Answer memory card rejected: cardId=" + CARD + " reason=KEY_FACT_QUANTITY");
        assertThat(logs).doesNotContain("QZXMARKER").doesNotContain("Yıllık").doesNotContain("izin");
    }

    @Test
    void lookup_whenAParaphraseCarriesNoOtherKeyFact_isServedAndNothingIsRejected() {
        stubCard(KnowledgeCardState.VERIFIED, NOW.plusSeconds(3600), 0.96);
        when(provenance.sourceChunks(eq(READER), any())).thenReturn(List.of(visible(HASH)));

        assertThat(memory.lookup(READER, EMBEDDING, "Bir çalışanın yıllık izni kaç gündür?")).isPresent();

        assertThat(registry.find("verso.qa.knowledge.rejections").counters())
                .allSatisfy(c -> assertThat(c.count()).isZero());
        assertThat(lookups("served")).isEqualTo(1);
        verify(retrieval, never()).relevance(anyString(), anyString());
    }

    @Test
    void lookup_whenTheFirstCardConflicts_triesTheNextCandidate() {
        UUID other = UUID.randomUUID();
        stubCard(KnowledgeCardState.VERIFIED, NOW.plusSeconds(3600), 0.99);
        when(cards.nearestVerified(any(), eq("bge-m3"), eq(NOW), eq(3)))
                .thenReturn(List.of(new Candidate(other, 0.99), new Candidate(CARD, 0.97)));
        when(cards.find(other)).thenReturn(Optional.of(card(other, "Yıllık izin 20 gün mü, değil mi?",
                KnowledgeCardState.VERIFIED, NOW.plusSeconds(3600))));
        when(provenance.sourceChunks(eq(READER), any())).thenReturn(List.of(visible(HASH)));

        assertThat(memory.lookup(READER, EMBEDDING, QUESTION)).get().extracting(AnswerMemory.Served::cardId)
                .isEqualTo(CARD);
        assertThat(rejections("key_fact_negation")).isEqualTo(1);
    }

    @Test
    void rerankCheck_servesOnlyAConfirmedPair_andAMissingScoreIsNotAConfirmation() {
        when(retrieval.rerankerEnabled()).thenReturn(true);
        AnswerMemory checked = memory(new RerankCheck(true, 0.80));
        stubCard(KnowledgeCardState.VERIFIED, NOW.plusSeconds(3600), 0.97);
        when(provenance.sourceChunks(eq(READER), any())).thenReturn(List.of(visible(HASH)));

        when(retrieval.relevance(QUESTION, QUESTION)).thenReturn(OptionalDouble.of(0.79));
        assertThat(checked.lookup(READER, EMBEDDING, QUESTION)).as("below min-score").isEmpty();
        when(retrieval.relevance(QUESTION, QUESTION)).thenReturn(OptionalDouble.empty());
        assertThat(checked.lookup(READER, EMBEDDING, QUESTION)).as("reranker failed").isEmpty();
        when(retrieval.relevance(QUESTION, QUESTION)).thenReturn(OptionalDouble.of(0.80));
        assertThat(checked.lookup(READER, EMBEDDING, QUESTION)).as("confirmed").isPresent();

        assertThat(rejections("rerank_low")).isEqualTo(1);
        assertThat(rejections("rerank_unavailable")).isEqualTo(1);
    }

    @Test
    void rerankCheck_isNeverCalledForACardTheGuardOrTheReaderAlreadyRefused() {
        when(retrieval.rerankerEnabled()).thenReturn(true);
        AnswerMemory checked = memory(new RerankCheck(true, 0.80));
        stubCard(KnowledgeCardState.VERIFIED, NOW.plusSeconds(3600), 0.99);
        when(provenance.sourceChunks(eq(READER), any())).thenReturn(List.of());

        assertThat(checked.lookup(READER, EMBEDDING, NEAR_MISS)).as("guard").isEmpty();
        assertThat(checked.lookup(READER, EMBEDDING, QUESTION)).as("not readable").isEmpty();

        verify(retrieval, never()).relevance(anyString(), anyString());
        assertThat(lookups("not_readable")).isEqualTo(1);
    }

    @Test
    void rerankCheck_withoutTheReranker_isRefusedAtStartup() {
        when(retrieval.rerankerEnabled()).thenReturn(false);
        assertThatThrownBy(() -> memory(new RerankCheck(true, 0.80)))
                .hasMessageContaining("verso.document.reranker.enabled");
    }

    @Test
    void lookup_whenMatchedVerifiedAndReadable_servesTheVerifiedTextWithServerMappedCitations() {
        stubCard(KnowledgeCardState.VERIFIED, NOW.plusSeconds(3600), 0.95);
        when(provenance.sourceChunks(eq(READER), any())).thenReturn(List.of(visible(HASH)));

        var served = memory.lookup(READER, EMBEDDING, QUESTION).orElseThrow();

        assertThat(served.cardId()).isEqualTo(CARD);
        assertThat(served.answer()).isEqualTo("Yıllık izin on dört gündür [1].");
        assertThat(served.citations()).singleElement().satisfies(c -> {
            assertThat(c.number()).isEqualTo(1);
            assertThat(c.documentId()).isEqualTo(DOC);
            assertThat(c.fileName()).isEqualTo("izin.pdf");
            assertThat(c.excerpt()).contains("on dört");
        });
        assertThat(served.memory().expiresAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void lookup_whenBelowTheThreshold_readsNoCardAndNoDocument() {
        when(cards.nearestVerified(any(), anyString(), any(), anyInt())).thenReturn(List.of(new Candidate(CARD, 0.92)));
        assertThat(memory.lookup(READER, EMBEDDING, QUESTION)).isEmpty();
        verify(cards, never()).find(any());
        verify(provenance, never()).sourceChunks(any(), any());
    }

    @Test
    void lookup_whenTheReaderCannotReadEverySource_orTheTextChanged_servesNothing() {
        stubCard(KnowledgeCardState.VERIFIED, NOW.plusSeconds(3600), 0.99);
        when(provenance.sourceChunks(eq(READER), any())).thenReturn(List.of());
        assertThat(memory.lookup(READER, EMBEDDING, QUESTION)).as("not readable").isEmpty();
        when(provenance.sourceChunks(eq(READER), any())).thenReturn(List.of(visible("e".repeat(64))));
        assertThat(memory.lookup(READER, EMBEDDING, QUESTION)).as("hash changed").isEmpty();
    }

    @Test
    void lookup_neverServesACardThatIsNoLongerVerifiedOrHasExpired() {
        for (KnowledgeCardState state : List.of(KnowledgeCardState.STALE, KnowledgeCardState.DISPUTED,
                KnowledgeCardState.ARCHIVED, KnowledgeCardState.DRAFT)) {
            stubCard(state, NOW.plusSeconds(3600), 0.99);
            when(provenance.sourceChunks(eq(READER), any())).thenReturn(List.of(visible(HASH)));
            assertThat(memory.lookup(READER, EMBEDDING, QUESTION)).as(state.name()).isEmpty();
        }
        stubCard(KnowledgeCardState.VERIFIED, NOW, 0.99);
        assertThat(memory.lookup(READER, EMBEDDING, QUESTION)).as("expired").isEmpty();
    }

    @Test
    void lookup_whenTheDatabaseFails_fallsBackToTheNormalPath() {
        when(cards.nearestVerified(any(), anyString(), any(), anyInt())).thenThrow(new QueryTimeoutException("x"));
        assertThat(memory.lookup(READER, EMBEDDING, QUESTION)).isEmpty();
    }

    private void stubCard(KnowledgeCardState state, Instant expiresAt, double similarity) {
        when(cards.nearestVerified(any(), eq("bge-m3"), eq(NOW), eq(3))).thenReturn(List.of(new Candidate(CARD, similarity)));
        when(cards.find(CARD)).thenReturn(Optional.of(card(CARD, QUESTION, state, expiresAt)));
        when(cards.bindings(any())).thenReturn(List.of(new Binding(UUID.randomUUID(), new SourcePage(DOC, 3), HASH)));
        when(cards.citations(any())).thenReturn(List.of(new CitationRow(1, DOC, 3, "PAGE")));
    }

    private static CardRow card(UUID id, String question, KnowledgeCardState state, Instant expiresAt) {
        return new CardRow(id, question, "Yıllık izin on dört gündür [1].", List.of(), state, null,
                List.of(COLLECTION), "acct-admin", 1, 1, 0, NOW.minusSeconds(60), expiresAt, NOW.minusSeconds(120),
                NOW.minusSeconds(60), "bge-m3");
    }

    private double rejections(String reason) {
        return registry.get("verso.qa.knowledge.rejections").tag("reason", reason).counter().count();
    }

    private double lookups(String outcome) {
        return registry.get("verso.qa.knowledge.lookups").tag("outcome", outcome).counter().count();
    }

    private String logs() {
        return appender.list.stream().map(e -> e.getFormattedMessage() + e.getMDCPropertyMap()
                + (e.getThrowableProxy() == null ? "" : e.getThrowableProxy().getMessage()))
                .collect(Collectors.joining("\n"));
    }

    private static SourceChunk visible(String hash) {
        return new SourceChunk(UUID.randomUUID(), DOC, 3, SourceUnit.PAGE, hash, COLLECTION, "izin.pdf", "İK",
                "Çalışanların yıllık izni on dört gündür. Beş yıldan sonra yirmi gün.");
    }
}
