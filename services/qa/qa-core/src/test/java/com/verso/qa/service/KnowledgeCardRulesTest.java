package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.document.api.dto.DocumentChange;
import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SourcePage;
import com.verso.document.api.enums.SourceUnit;
import com.verso.qa.api.dto.KnowledgeCardState;
import com.verso.qa.api.dto.KnowledgeStaleReason;
import com.verso.qa.config.QaKnowledgeProperties;
import com.verso.qa.config.QaKnowledgeProperties.RerankCheck;
import com.verso.qa.repository.KnowledgeCardRepository.Binding;
import com.verso.qa.repository.KnowledgeCardRepository.Candidate;
import com.verso.qa.service.KnowledgeCardRules.BindingState;
import com.verso.qa.service.KnowledgeCardRules.ScopeProblem;
import com.verso.qa.service.KnowledgeCardRules.VerifyStep;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The answer memory's rules (ADR-0049) without a database: state machine, hash binding, scope, matching, disputes. */
class KnowledgeCardRulesTest {

    private static final UUID DOC_A = UUID.randomUUID();
    private static final UUID DOC_B = UUID.randomUUID();
    private static final UUID COLLECTION_A = UUID.randomUUID();
    private static final UUID COLLECTION_B = UUID.randomUUID();
    private static final String HASH_1 = "a".repeat(64);
    private static final String HASH_2 = "b".repeat(64);

    // ---------------------------------------------------------------- state machine

    @Test
    void verifyStep_followsTheStateMachine_promotionOnlyFromDraftOrARestartedCycle() {
        assertThat(KnowledgeCardRules.verifyStep(KnowledgeCardState.DRAFT, null)).isEqualTo(VerifyStep.SAME_CYCLE);
        assertThat(KnowledgeCardRules.verifyStep(KnowledgeCardState.DISPUTED, null)).isEqualTo(VerifyStep.NEW_CYCLE);
        assertThat(KnowledgeCardRules.verifyStep(KnowledgeCardState.STALE, KnowledgeStaleReason.EXPIRED))
                .as("an expired card keeps its sources and can be verified again").isEqualTo(VerifyStep.NEW_CYCLE);
        assertThat(KnowledgeCardRules.verifyStep(KnowledgeCardState.VERIFIED, null)).isEqualTo(VerifyStep.REFUSED);
        assertThat(KnowledgeCardRules.verifyStep(KnowledgeCardState.ARCHIVED, null)).isEqualTo(VerifyStep.REFUSED);
    }

    @ParameterizedTest
    @EnumSource(value = KnowledgeStaleReason.class, names = "EXPIRED", mode = EnumSource.Mode.EXCLUDE)
    void verifyStep_whenASourceChanged_isRefused(KnowledgeStaleReason reason) {
        assertThat(KnowledgeCardRules.verifyStep(KnowledgeCardState.STALE, reason)).isEqualTo(VerifyStep.REFUSED);
    }

    @Test
    void afterVerification_needsTheRequiredDistinctVerifiers() {
        assertThat(KnowledgeCardRules.afterVerification(1, 1)).isEqualTo(KnowledgeCardState.VERIFIED);
        assertThat(KnowledgeCardRules.afterVerification(1, 2)).as("two-person rule").isEqualTo(KnowledgeCardState.DRAFT);
        assertThat(KnowledgeCardRules.afterVerification(2, 2)).isEqualTo(KnowledgeCardState.VERIFIED);
    }

    @Test
    void demotions_onlyFromLiveStates_archiveFromAnyButArchived() {
        assertThat(KnowledgeCardRules.canGoStale(KnowledgeCardState.VERIFIED)).isTrue();
        assertThat(KnowledgeCardRules.canGoStale(KnowledgeCardState.DRAFT)).isTrue();
        assertThat(KnowledgeCardRules.canGoStale(KnowledgeCardState.DISPUTED)).isTrue();
        assertThat(KnowledgeCardRules.canGoStale(KnowledgeCardState.STALE)).as("the first reason is kept").isFalse();
        assertThat(KnowledgeCardRules.canGoStale(KnowledgeCardState.ARCHIVED)).isFalse();
        for (KnowledgeCardState state : KnowledgeCardState.values()) {
            assertThat(KnowledgeCardRules.canArchive(state)).as(state.name()).isEqualTo(state != KnowledgeCardState.ARCHIVED);
        }
    }

    @Test
    void servable_onlyVerifiedAndBeforeExpiry() {
        Instant now = Instant.parse("2026-10-26T10:00:00Z");
        Instant later = now.plusSeconds(60);
        assertThat(KnowledgeCardRules.servable(KnowledgeCardState.VERIFIED, later, now)).isTrue();
        assertThat(KnowledgeCardRules.servable(KnowledgeCardState.VERIFIED, now, now)).as("expiry is exclusive").isFalse();
        assertThat(KnowledgeCardRules.servable(KnowledgeCardState.VERIFIED, null, now)).isFalse();
        for (KnowledgeCardState state : List.of(KnowledgeCardState.DRAFT, KnowledgeCardState.STALE,
                KnowledgeCardState.DISPUTED, KnowledgeCardState.ARCHIVED)) {
            assertThat(KnowledgeCardRules.servable(state, later, now)).as(state.name()).isFalse();
        }
    }

    @Test
    void staleReason_ofEveryDocumentChange() {
        assertThat(KnowledgeCardRules.staleReason(DocumentChange.Kind.DELETED)).isEqualTo(KnowledgeStaleReason.SOURCE_DELETED);
        assertThat(KnowledgeCardRules.staleReason(DocumentChange.Kind.REPLACED)).isEqualTo(KnowledgeStaleReason.SOURCE_REPLACED);
        assertThat(KnowledgeCardRules.staleReason(DocumentChange.Kind.MOVED)).isEqualTo(KnowledgeStaleReason.SOURCE_MOVED);
    }

    // ---------------------------------------------------------------- disputes

    @Test
    void disputed_atTheThreshold() {
        assertThat(KnowledgeCardRules.disputed(2, 3)).isFalse();
        assertThat(KnowledgeCardRules.disputed(3, 3)).isTrue();
        assertThat(KnowledgeCardRules.disputed(4, 3)).isTrue();
    }

    @Test
    void disputant_isStablePerCardAndUnlinkableAcrossCards() {
        UUID card = UUID.randomUUID();
        assertThat(KnowledgeCardRules.disputant(card, "acct-1")).hasSize(32)
                .isEqualTo(KnowledgeCardRules.disputant(card, "acct-1"));
        assertThat(KnowledgeCardRules.disputant(card, "acct-1")).isNotEqualTo(KnowledgeCardRules.disputant(card, "acct-2"));
        assertThat(KnowledgeCardRules.disputant(card, "acct-1"))
                .as("another card: another pseudonym").isNotEqualTo(KnowledgeCardRules.disputant(UUID.randomUUID(), "acct-1"));
    }

    @Test
    void disputeThreshold_belowTwo_isRefusedAtStartup() {
        assertThatThrownBy(() -> new QaKnowledgeProperties(true, 0.93, 3, Duration.ofDays(90), false, 1,
                Duration.ofDays(365), eval(), RerankCheck.OFF)).hasMessageContaining("dispute-threshold");
        assertThatThrownBy(() -> new QaKnowledgeProperties(true, 0.5, 3, Duration.ofDays(90), false, 3,
                Duration.ofDays(365), eval(), RerankCheck.OFF)).hasMessageContaining("match-threshold");
        assertThat(new QaKnowledgeProperties(true, 0.93, 3, Duration.ofDays(90), true, 3, Duration.ofDays(365), eval(),
                RerankCheck.OFF).requiredVerifiers()).isEqualTo(2);
    }

    @Test
    void rerankCheck_minScoreOutsideZeroToOne_isRefusedAtStartup() {
        assertThatThrownBy(() -> new RerankCheck(true, 0)).hasMessageContaining("rerank-check.min-score");
        assertThatThrownBy(() -> new RerankCheck(true, 1)).hasMessageContaining("rerank-check.min-score");
        assertThat(RerankCheck.OFF.enabled()).isFalse();
    }

    // ---------------------------------------------------------------- matching

    @Test
    void matching_keepsOnlyCandidatesAtOrAboveTheThreshold_inOrder() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<Candidate> found = List.of(new Candidate(a, 0.97), new Candidate(b, 0.93),
                new Candidate(UUID.randomUUID(), 0.9299), new Candidate(UUID.randomUUID(), Double.NaN));
        assertThat(KnowledgeCardRules.matching(found, 0.93)).extracting(Candidate::cardId).containsExactly(a, b);
        assertThat(KnowledgeCardRules.matching(found, 0.98)).isEmpty();
    }

    // ---------------------------------------------------------------- hash binding

    @Test
    void check_isIntactOnlyWhileEveryBoundHashIsOnItsPage() {
        List<Binding> bound = List.of(binding(DOC_A, 1, HASH_1), binding(DOC_A, 2, HASH_2));
        List<SourceChunk> same = List.of(chunk(DOC_A, 1, HASH_1, COLLECTION_A), chunk(DOC_A, 2, HASH_2, COLLECTION_A));
        assertThat(KnowledgeCardRules.check(bound, same)).isEqualTo(BindingState.INTACT);
        // Re-embedded with the same text: new chunk ids, same hashes, still intact.
        assertThat(KnowledgeCardRules.check(bound, List.of(chunk(DOC_A, 1, HASH_1, COLLECTION_A),
                chunk(DOC_A, 2, HASH_2, COLLECTION_A)))).isEqualTo(BindingState.INTACT);
        assertThat(KnowledgeCardRules.check(bound, List.of(chunk(DOC_A, 1, HASH_1, COLLECTION_A))))
                .as("a page gone (deleted, or not readable by this reader)").isEqualTo(BindingState.BROKEN);
        assertThat(KnowledgeCardRules.check(bound, List.of(chunk(DOC_A, 1, HASH_1, COLLECTION_A),
                chunk(DOC_A, 2, "c".repeat(64), COLLECTION_A)))).as("text changed").isEqualTo(BindingState.BROKEN);
        assertThat(KnowledgeCardRules.check(bound, List.of(chunk(DOC_A, 1, HASH_1, COLLECTION_A),
                chunk(DOC_A, 3, HASH_2, COLLECTION_A)))).as("same text on another page").isEqualTo(BindingState.BROKEN);
        assertThat(KnowledgeCardRules.check(List.of(), same)).as("nothing bound is never intact").isEqualTo(BindingState.BROKEN);
    }

    @Test
    void currentChunkIds_areTheChunksCarryingBoundText() {
        SourceChunk kept = chunk(DOC_A, 1, HASH_1, COLLECTION_A);
        SourceChunk other = chunk(DOC_A, 1, HASH_2, COLLECTION_A);
        assertThat(KnowledgeCardRules.currentChunkIds(List.of(binding(DOC_A, 1, HASH_1)), List.of(kept, other)))
                .containsExactly(kept.chunkId());
    }

    // ---------------------------------------------------------------- scope

    @Test
    void scope_isTheCollectionsOfAllCitedDocuments() {
        List<SourcePage> cited = List.of(new SourcePage(DOC_A, 1), new SourcePage(DOC_B, 4));
        var scope = KnowledgeCardRules.scope(cited, List.of(chunk(DOC_A, 1, HASH_1, COLLECTION_A),
                chunk(DOC_B, 4, HASH_2, COLLECTION_B), chunk(DOC_A, 1, HASH_2, COLLECTION_A)));
        assertThat(scope.refusal()).isEmpty();
        assertThat(scope.collections()).containsExactly(COLLECTION_A, COLLECTION_B);
    }

    @Test
    void scope_refusesAPageTheAdminCannotReadAndAPersonalDocument() {
        List<SourcePage> cited = List.of(new SourcePage(DOC_A, 1), new SourcePage(DOC_B, 4));
        assertThat(KnowledgeCardRules.scope(cited, List.of(chunk(DOC_A, 1, HASH_1, COLLECTION_A))).refusal())
                .contains(ScopeProblem.SOURCE_UNAVAILABLE);
        assertThat(KnowledgeCardRules.scope(cited, List.of(chunk(DOC_A, 1, HASH_1, COLLECTION_A),
                chunk(DOC_B, 4, HASH_2, null))).refusal()).contains(ScopeProblem.PERSONAL_SOURCE);
        assertThat(KnowledgeCardRules.scope(List.of(), List.of()).refusal()).contains(ScopeProblem.SOURCE_UNAVAILABLE);
    }

    private static Binding binding(UUID document, int page, String hash) {
        return new Binding(UUID.randomUUID(), new SourcePage(document, page), hash);
    }

    private static SourceChunk chunk(UUID document, int page, String hash, UUID collection) {
        return new SourceChunk(UUID.randomUUID(), document, page, SourceUnit.PAGE, hash, collection, null, null, null);
    }

    static QaKnowledgeProperties.Eval eval() {
        return new QaKnowledgeProperties.Eval(true, "0 30 2 * * *", "Europe/Istanbul", 5, 1000, 0.02, 0.10,
                Duration.ofHours(1), Duration.ofDays(180));
    }
}
