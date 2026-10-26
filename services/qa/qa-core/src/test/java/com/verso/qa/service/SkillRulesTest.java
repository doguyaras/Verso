package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.api.dto.DocumentChange;
import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SourcePage;
import com.verso.document.api.enums.SourceUnit;
import com.verso.qa.api.dto.SkillStaleReason;
import com.verso.qa.api.dto.SkillState;
import com.verso.qa.repository.KnowledgeCardRepository.Binding;
import com.verso.qa.service.SkillRules.GoldenCheck;
import com.verso.qa.service.SkillRules.ScopeProblem;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Skills' rules (ADR-0053) without a database: state machine, scope intersection, hash binding, approval, names. */
class SkillRulesTest {

    private static final UUID DOC_A = UUID.randomUUID();
    private static final UUID DOC_B = UUID.randomUUID();
    private static final UUID HUKUK = UUID.randomUUID();
    private static final UUID IK = UUID.randomUUID();
    private static final String HASH_1 = "a".repeat(64);
    private static final String HASH_2 = "b".repeat(64);

    // ---------------------------------------------------------------- state machine

    @Test
    void stateMachine_onlyADraftIsApproved_andTheSystemOnlyDemotes() {
        assertThat(SkillRules.canApprove(SkillState.DRAFT)).isTrue();
        for (SkillState state : List.of(SkillState.APPROVED, SkillState.STALE, SkillState.ARCHIVED)) {
            assertThat(SkillRules.canApprove(state)).as(state.name()).isFalse();
        }
        assertThat(SkillRules.afterApproval(1, 1)).isEqualTo(SkillState.APPROVED);
        assertThat(SkillRules.afterApproval(1, 2)).as("two-person rule").isEqualTo(SkillState.DRAFT);
        assertThat(SkillRules.afterApproval(2, 2)).isEqualTo(SkillState.APPROVED);
        assertThat(SkillRules.canGoStale(SkillState.DRAFT)).isTrue();
        assertThat(SkillRules.canGoStale(SkillState.APPROVED)).isTrue();
        assertThat(SkillRules.canGoStale(SkillState.STALE)).as("the first reason is kept").isFalse();
        assertThat(SkillRules.canGoStale(SkillState.ARCHIVED)).isFalse();
        assertThat(SkillRules.canEdit(SkillState.APPROVED)).as("a new version, back to DRAFT").isTrue();
        assertThat(SkillRules.canEdit(SkillState.STALE)).as("a stale skill needs a new draft").isFalse();
        assertThat(SkillRules.canArchive(SkillState.STALE)).isTrue();
        assertThat(SkillRules.canArchive(SkillState.ARCHIVED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(SkillState.class)
    void servable_onlyAnApprovedSkill(SkillState state) {
        assertThat(SkillRules.servable(state)).isEqualTo(state == SkillState.APPROVED);
    }

    @Test
    void staleReason_followsTheDocumentChange() {
        assertThat(SkillRules.staleReason(DocumentChange.Kind.DELETED)).isEqualTo(SkillStaleReason.SOURCE_DELETED);
        assertThat(SkillRules.staleReason(DocumentChange.Kind.REPLACED)).isEqualTo(SkillStaleReason.SOURCE_REPLACED);
        assertThat(SkillRules.staleReason(DocumentChange.Kind.MOVED)).isEqualTo(SkillStaleReason.SOURCE_MOVED);
    }

    // ---------------------------------------------------------------- scope

    @Test
    void scope_isEveryCollectionOfEverySource_andAReaderNeedsAllOfThem() {
        SkillRules.Scope scope = SkillRules.scope(List.of(chunk(DOC_A, 1, HASH_1, HUKUK), chunk(DOC_B, 2, HASH_2, IK),
                chunk(DOC_A, 2, HASH_2, HUKUK)));
        assertThat(scope.refusal()).isEmpty();
        assertThat(scope.collections()).containsExactly(HUKUK, IK);
        // The audience is the intersection: reaching one of two collections is not enough.
        assertThat(SkillRules.readableBy(scope.collections(), Set.of(HUKUK, IK, UUID.randomUUID()))).isTrue();
        assertThat(SkillRules.readableBy(scope.collections(), Set.of(HUKUK))).isFalse();
        assertThat(SkillRules.readableBy(scope.collections(), Set.of())).isFalse();
        assertThat(SkillRules.readableBy(List.of(), Set.of(HUKUK))).as("no scope is never readable").isFalse();
    }

    @Test
    void scope_refusesAPersonalSourceAndAnEmptyBinding() {
        assertThat(SkillRules.scope(List.of(chunk(DOC_A, 1, HASH_1, HUKUK), chunk(DOC_B, 1, HASH_2, null))).refusal())
                .contains(ScopeProblem.PERSONAL_SOURCE);
        assertThat(SkillRules.scope(List.of()).refusal()).contains(ScopeProblem.SOURCE_UNAVAILABLE);
    }

    // ---------------------------------------------------------------- hash binding

    @Test
    void binding_isIntactOnlyWhileEveryBoundHashIsOnItsPage() {
        List<Binding> bound = List.of(new Binding(UUID.randomUUID(), new SourcePage(DOC_A, 1), HASH_1),
                new Binding(UUID.randomUUID(), new SourcePage(DOC_B, 2), HASH_2));
        List<SourceChunk> same = List.of(chunk(DOC_A, 1, HASH_1, HUKUK), chunk(DOC_B, 2, HASH_2, IK));
        assertThat(SkillRules.intact(bound, same)).as("matched by page and hash, new chunk ids are fine").isTrue();
        assertThat(SkillRules.intact(bound, List.of(chunk(DOC_A, 1, HASH_1, HUKUK))))
                .as("a page the reader cannot read is absent: broken").isFalse();
        assertThat(SkillRules.intact(bound, List.of(chunk(DOC_A, 1, HASH_1, HUKUK), chunk(DOC_B, 2, HASH_1, IK))))
                .as("changed text").isFalse();
        assertThat(SkillRules.intact(bound, List.of(chunk(DOC_A, 1, HASH_1, HUKUK), chunk(DOC_B, 3, HASH_2, IK))))
                .as("the same text on another page").isFalse();
        assertThat(SkillRules.intact(List.of(), same)).as("no binding is never intact").isFalse();
        assertThat(SkillRules.pages(bound)).containsExactly(new SourcePage(DOC_A, 1), new SourcePage(DOC_B, 2));
    }

    // ---------------------------------------------------------------- approval

    @Test
    void approval_needsTwoToFiveGoldenCasesATriggerAStepAndEveryStepGrounded() {
        SourcePage page = new SourcePage(DOC_A, 1);
        Set<SourcePage> bound = Set.of(page);
        Map<SourcePage, String> text = Map.of(page, "Yıllık izin talebi İK portalından açılır, onay 3 iş günüdür.");
        List<GoldenCheck> two = List.of(new GoldenCheck(page, null), new GoldenCheck(page, "3 iş günü"));
        assertThat(SkillRules.approvalProblems(2, List.of(true, true), 1, two, bound, text::get)).isEmpty();
        assertThat(SkillRules.approvalProblems(2, List.of(true, true), 1, two.subList(0, 1), bound, text::get))
                .containsExactly("GOLDEN_COUNT");
        List<GoldenCheck> six = List.of(two.get(0), two.get(0), two.get(0), two.get(0), two.get(0), two.get(0));
        assertThat(SkillRules.approvalProblems(2, List.of(true, true), 1, six, bound, text::get))
                .containsExactly("GOLDEN_COUNT");
        assertThat(SkillRules.approvalProblems(2, List.of(true, false), 0, two, bound, text::get))
                .containsExactly("NO_TRIGGER", "STEP_UNGROUNDED");
        assertThat(SkillRules.approvalProblems(0, List.of(), 1, two, bound, text::get)).containsExactly("NO_STEP");
        assertThat(SkillRules.approvalProblems(1, List.of(true), 1,
                List.of(new GoldenCheck(page, null), new GoldenCheck(new SourcePage(DOC_B, 9), null)), bound, text::get))
                .as("the expected page must be a source of the skill").containsExactly("GOLDEN_PAGE");
        assertThat(SkillRules.approvalProblems(1, List.of(true), 1,
                List.of(new GoldenCheck(page, null), new GoldenCheck(page, "5 iş günü")), bound, text::get))
                .as("the fact must stand on its page").containsExactly("GOLDEN_FACT");
        assertThat(SkillRules.approvalProblems(1, List.of(true), 1,
                List.of(new GoldenCheck(page, null), new GoldenCheck(page, "IK PORTALINDAN")), bound, text::get))
                .as("Turkish folding: İK = ik").isEmpty();
    }

    // ---------------------------------------------------------------- names

    @Test
    void slug_isASpecValidName() {
        assertThat(SkillRules.slug("Yıllık İzin Talebi nasıl açılır?")).isEqualTo("yillik-izin-talebi-nasil-acilir");
        assertThat(SkillRules.slug("  ---  ")).isEqualTo("prosedur");
        assertThat(SkillRules.slug(null)).isEqualTo("prosedur");
        String long70 = "a".repeat(30) + " " + "b".repeat(30) + " " + "c".repeat(30);
        String cut = SkillRules.slug(long70);
        assertThat(cut).hasSizeLessThanOrEqualTo(64).isEqualTo("a".repeat(30) + "-" + "b".repeat(30));
        assertThat(SkillRules.validName(cut)).isTrue();
        assertThat(SkillRules.withSuffix("vpn-kurulumu", 1)).isEqualTo("vpn-kurulumu");
        assertThat(SkillRules.withSuffix("vpn-kurulumu", 2)).isEqualTo("vpn-kurulumu-2");
        assertThat(SkillRules.withSuffix("x".repeat(64), 12)).hasSize(64).endsWith("-12");
        assertThat(SkillRules.validName("Vpn")).isFalse();
        assertThat(SkillRules.validName("vpn--kurulum")).isFalse();
        assertThat(SkillRules.validName("-vpn")).isFalse();
        assertThat(SkillRules.validName("x".repeat(65))).isFalse();
    }

    @Test
    void procedural_recognisesHowDoIQuestions() {
        assertThat(SkillRules.procedural("VPN nasıl kurulur?")).isTrue();
        assertThat(SkillRules.procedural("Masraf iadesi için başvuru adımları neler?")).isTrue();
        assertThat(SkillRules.procedural("How do I reset my password?")).isTrue();
        assertThat(SkillRules.procedural("Yıllık izin kaç gündür?")).isFalse();
        assertThat(SkillRules.procedural(null)).isFalse();
    }

    private static SourceChunk chunk(UUID document, int page, String hash, UUID collection) {
        return new SourceChunk(UUID.randomUUID(), document, page, SourceUnit.PAGE, hash, collection, null, null, null);
    }
}
