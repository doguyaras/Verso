package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SourcePage;
import com.verso.document.api.enums.SourceUnit;
import com.verso.qa.service.SkillTemplates.DraftStep;
import com.verso.qa.service.SkillTemplates.GoldenSeed;
import com.verso.qa.service.SkillTemplates.SourceAnswer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The template assembly of a skill draft (ADR-0053): steps from verified answers, their bindings and grounding. */
class SkillTemplatesTest {

    private static final UUID DOC = UUID.randomUUID();
    private static final UUID COLLECTION = UUID.randomUUID();
    private static final SourcePage PAGE_1 = new SourcePage(DOC, 1);
    private static final SourcePage PAGE_2 = new SourcePage(DOC, 2);
    private static final SourceChunk CHUNK_1 = chunk(PAGE_1, "VPN istemcisini yazılım merkezinden kurun. "
            + "Kurulumdan sonra bilgisayarı yeniden başlatın.");
    private static final SourceChunk CHUNK_2 = chunk(PAGE_2, "Bağlantı adresi vpn.sirket.local, port 443 kullanılır. "
            + "Hata kodu VPN-204 görülürse BT masasını arayın.");

    @Test
    void aListAnswer_givesOneStepPerItem_boundToThePagesItsMarkersCite() {
        SourceAnswer answer = answer("VPN nasıl kurulur?", """
                VPN kurulumu şöyledir:
                1. VPN istemcisini yazılım merkezinden kurun [1].
                2. Bağlantı adresi olarak vpn.sirket.local ve port 443 girin [2].
                3. VPN-204 hatasında BT masasını arayın [2].""");
        List<DraftStep> steps = SkillTemplates.steps(List.of(answer), 12, 400);

        assertThat(steps).extracting(DraftStep::instruction).containsExactly(
                "VPN istemcisini yazılım merkezinden kurun.",
                "Bağlantı adresi olarak vpn.sirket.local ve port 443 girin.",
                "VPN-204 hatasında BT masasını arayın.");
        assertThat(steps.get(0).sources()).containsExactly(CHUNK_1);
        assertThat(steps.get(1).sources()).containsExactly(CHUNK_2);
        assertThat(steps).allSatisfy(step -> assertThat(step.grounded()).as(step.instruction()).isTrue());
    }

    @Test
    void aProseAnswer_isOneStep_boundToEveryCitedPage_withoutMarkers() {
        SourceAnswer answer = answer("VPN nasıl kurulur?", "İstemciyi kurun [1] ve port 443 ile bağlanın [2].");
        List<DraftStep> steps = SkillTemplates.steps(List.of(answer), 12, 400);

        assertThat(steps).hasSize(1);
        assertThat(steps.getFirst().instruction()).isEqualTo("İstemciyi kurun ve port 443 ile bağlanın.");
        assertThat(steps.getFirst().sources()).containsExactly(CHUNK_1, CHUNK_2);
    }

    @Test
    void grounding_flagsANumberOrCodeTheSourcesDoNotGive() {
        assertThat(SkillTemplates.grounded("Port 443 kullanılır.", List.of(CHUNK_2))).isTrue();
        assertThat(SkillTemplates.grounded("Port 8443 kullanılır.", List.of(CHUNK_2))).isFalse();
        assertThat(SkillTemplates.grounded("VPN-205 hatasında BT masasını arayın.", List.of(CHUNK_2))).isFalse();
        assertThat(SkillTemplates.grounded("Bilgisayarı yeniden başlatın.", List.of(CHUNK_1)))
                .as("a step without numbers or codes has nothing to check").isTrue();
        List<DraftStep> steps = SkillTemplates.steps(List.of(answer("Port?", "1. Port 8443 girin [2].\n2. Kurun [1].")),
                12, 400);
        assertThat(steps.getFirst().grounded()).as("the template keeps the flag for the admin").isFalse();
    }

    @Test
    void steps_areCutCappedAndDistinct_andHiddenCharactersRemoved() {
        String longItem = "Kurun " + "çok uzun bir açıklama ".repeat(40);
        SourceAnswer answer = answer("q", "1. " + longItem + "[1]\n2. Kurun‮ ters [1]\n3. " + longItem + "[1]");
        List<DraftStep> steps = SkillTemplates.steps(List.of(answer), 12, 120);
        assertThat(steps).hasSize(2);
        assertThat(steps.getFirst().instruction()).hasSizeLessThanOrEqualTo(120).endsWith("…");
        assertThat(steps.get(1).instruction()).doesNotContain("‮").isEqualTo("Kurun ters");
        assertThat(SkillTemplates.steps(List.of(answer, answer("r", "1. A [1]\n2. B [1]\n3. C [1]")), 4, 400)).hasSize(4);
    }

    @Test
    void triggersGoldenAndDescription_comeFromTheVerifiedQuestions() {
        SourceAnswer first = new SourceAnswer("VPN nasıl kurulur?", "1. Kurun [1].\n2. Bağlanın [2].", citations(),
                List.of(CHUNK_1, CHUNK_2), List.of("port 999", "yazılım merkezinden"));
        SourceAnswer second = answer("vpn nasıl kurulur?", "Kurun [1].");
        SourceAnswer third = answer("VPN-204 hatası ne demek?", "BT masasını arayın [2].");
        assertThat(SkillTemplates.triggers(List.of(first, second, third)))
                .as("distinct after Turkish folding").containsExactly("VPN nasıl kurulur?", "VPN-204 hatası ne demek?");
        List<GoldenSeed> golden = SkillTemplates.golden(List.of(first, third));
        assertThat(golden).extracting(GoldenSeed::page).containsExactly(PAGE_1, PAGE_1);
        assertThat(golden.getFirst().fact()).as("only a key fact that the page states").isEqualTo("yazılım merkezinden");
        assertThat(golden.get(1).fact()).isNull();
        String description = SkillTemplates.description(List.of("VPN nasıl kurulur?", "Port \"x\"?"), 3);
        assertThat(description).isEqualTo("VPN nasıl kurulur: 3 adımlı onaylı prosedür. Şu tür sorularda kullanın: "
                + "\"VPN nasıl kurulur?\"; \"Port 'x'?\".");
        assertThat(SkillTemplates.description(List.of("x".repeat(2000)), 1)).hasSize(1024);
    }

    private static SourceAnswer answer(String question, String text) {
        return new SourceAnswer(question, text, citations(), List.of(CHUNK_1, CHUNK_2), List.of());
    }

    private static Map<Integer, SourcePage> citations() {
        Map<Integer, SourcePage> citations = new LinkedHashMap<>();
        citations.put(1, PAGE_1);
        citations.put(2, PAGE_2);
        return citations;
    }

    private static SourceChunk chunk(SourcePage page, String text) {
        return new SourceChunk(UUID.randomUUID(), page.documentId(), page.pageNumber(), SourceUnit.PAGE,
                "c".repeat(64), COLLECTION, "vpn.pdf", "BT", text);
    }
}
