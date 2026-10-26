package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.verso.document.api.DocumentProvenance;
import com.verso.document.api.dto.QuestionEmbedding;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SourcePage;
import com.verso.document.api.enums.SourceUnit;
import com.verso.qa.api.dto.KnowledgeCardState;
import com.verso.qa.config.QaKnowledgeProperties;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/**
 * The serving rules of the answer memory (ADR-0049) with stubbed repositories: a card answers only when it matches
 * above the threshold, is VERIFIED and unexpired, and every bound chunk is visible to this reader with its hash.
 */
class AnswerMemoryTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final UUID CARD = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();
    private static final UUID COLLECTION = UUID.randomUUID();
    private static final String HASH = "d".repeat(64);
    private static final Reader READER = new Reader("acct-reader", List.of("/hukuk"));
    private static final QuestionEmbedding EMBEDDING = new QuestionEmbedding("bge-m3", new float[] {1f, 0f});

    private final KnowledgeCardRepository cards = mock(KnowledgeCardRepository.class);
    private final DocumentProvenance provenance = mock(DocumentProvenance.class);
    private final QaProperties qa = new QaProperties(5, 0.47, 0.44, 0.045, 0.50, 1500, 1, Duration.ofSeconds(5), 2,
            Duration.ofSeconds(15), Duration.ofSeconds(90), Duration.ofSeconds(30));
    private final AnswerMemory memory = new AnswerMemory(cards, provenance, new CitationExtractor(qa),
            new QaKnowledgeProperties(true, 0.93, 3, Duration.ofDays(90), false, 3, Duration.ofDays(365),
                    KnowledgeCardRulesTest.eval()), Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());

    @Test
    void lookup_whenMatchedVerifiedAndReadable_servesTheVerifiedTextWithServerMappedCitations() {
        stubCard(KnowledgeCardState.VERIFIED, NOW.plusSeconds(3600), 0.95);
        when(provenance.sourceChunks(eq(READER), any())).thenReturn(List.of(visible(HASH)));

        var served = memory.lookup(READER, EMBEDDING).orElseThrow();

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
        assertThat(memory.lookup(READER, EMBEDDING)).isEmpty();
        verify(cards, never()).find(any());
        verify(provenance, never()).sourceChunks(any(), any());
    }

    @Test
    void lookup_whenTheReaderCannotReadEverySource_orTheTextChanged_servesNothing() {
        stubCard(KnowledgeCardState.VERIFIED, NOW.plusSeconds(3600), 0.99);
        when(provenance.sourceChunks(eq(READER), any())).thenReturn(List.of());
        assertThat(memory.lookup(READER, EMBEDDING)).as("not readable").isEmpty();
        when(provenance.sourceChunks(eq(READER), any())).thenReturn(List.of(visible("e".repeat(64))));
        assertThat(memory.lookup(READER, EMBEDDING)).as("hash changed").isEmpty();
    }

    @Test
    void lookup_neverServesACardThatIsNoLongerVerifiedOrHasExpired() {
        for (KnowledgeCardState state : List.of(KnowledgeCardState.STALE, KnowledgeCardState.DISPUTED,
                KnowledgeCardState.ARCHIVED, KnowledgeCardState.DRAFT)) {
            stubCard(state, NOW.plusSeconds(3600), 0.99);
            when(provenance.sourceChunks(eq(READER), any())).thenReturn(List.of(visible(HASH)));
            assertThat(memory.lookup(READER, EMBEDDING)).as(state.name()).isEmpty();
        }
        stubCard(KnowledgeCardState.VERIFIED, NOW, 0.99);
        assertThat(memory.lookup(READER, EMBEDDING)).as("expired").isEmpty();
    }

    @Test
    void lookup_whenTheDatabaseFails_fallsBackToTheNormalPath() {
        when(cards.nearestVerified(any(), anyString(), any(), anyInt())).thenThrow(new QueryTimeoutException("x"));
        assertThat(memory.lookup(READER, EMBEDDING)).isEmpty();
    }

    private void stubCard(KnowledgeCardState state, Instant expiresAt, double similarity) {
        when(cards.nearestVerified(any(), eq("bge-m3"), eq(NOW), eq(3))).thenReturn(List.of(new Candidate(CARD, similarity)));
        when(cards.find(CARD)).thenReturn(Optional.of(new CardRow(CARD, "Yıllık izin kaç gün?",
                "Yıllık izin on dört gündür [1].", List.of(), state, null, List.of(COLLECTION), "acct-admin", 1, 1, 0,
                NOW.minusSeconds(60), expiresAt, NOW.minusSeconds(120), NOW.minusSeconds(60), "bge-m3")));
        when(cards.bindings(CARD)).thenReturn(List.of(new Binding(UUID.randomUUID(), new SourcePage(DOC, 3), HASH)));
        when(cards.citations(CARD)).thenReturn(List.of(new CitationRow(1, DOC, 3, "PAGE")));
    }

    private static SourceChunk visible(String hash) {
        return new SourceChunk(UUID.randomUUID(), DOC, 3, SourceUnit.PAGE, hash, COLLECTION, "izin.pdf", "İK",
                "Çalışanların yıllık izni on dört gündür. Beş yıldan sonra yirmi gün.");
    }
}
