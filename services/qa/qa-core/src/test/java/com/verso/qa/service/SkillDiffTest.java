package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.qa.api.dto.SkillVersionDiff;
import com.verso.qa.api.dto.SkillVersionDiff.Change;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The version diff of a skill (ADR-0053): field by field, texts only for an admin who reads the sources. */
class SkillDiffTest {

    private static final SkillDiff.Snapshot V1 = new SkillDiff.Snapshot(1, "vpn", "VPN kurulumu.",
            List.of("Kurun.", "Bağlanın."), List.of("VPN nasıl kurulur?"), List.of());
    private static final SkillDiff.Snapshot V2 = new SkillDiff.Snapshot(2, "vpn-kurulumu", "VPN kurulumu.",
            List.of("İstemciyi kurun."), List.of("VPN nasıl kurulur?", "VPN kurulumu nedir?"), List.of("q → d s. 1"));

    @Test
    void diff_listsChangedAddedAndRemovedFieldsInOrder() {
        SkillVersionDiff diff = SkillDiff.diff(V1, V2, true);
        assertThat(diff.from()).isEqualTo(1);
        assertThat(diff.to()).isEqualTo(2);
        assertThat(diff.changes()).containsExactly(
                new Change("name", 0, "CHANGED", "vpn", "vpn-kurulumu"),
                new Change("step", 1, "CHANGED", "Kurun.", "İstemciyi kurun."),
                new Change("step", 2, "REMOVED", "Bağlanın.", null),
                new Change("trigger", 2, "ADDED", null, "VPN kurulumu nedir?"),
                new Change("goldenCase", 1, "ADDED", null, "q → d s. 1"));
    }

    @Test
    void diff_withoutAccess_saysWhatChanged_notHow_butNamesStayVisible() {
        SkillVersionDiff diff = SkillDiff.diff(V1, V2, false);
        assertThat(diff.changes()).hasSize(5);
        assertThat(diff.changes().getFirst().after()).as("the name is an identifier").isEqualTo("vpn-kurulumu");
        assertThat(diff.changes().subList(1, 5)).allSatisfy(change -> {
            assertThat(change.before()).isNull();
            assertThat(change.after()).isNull();
        });
        assertThat(SkillDiff.diff(V1, V1, true).changes()).isEmpty();
    }
}
