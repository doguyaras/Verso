package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.qa.api.dto.ClusterTrend;
import com.verso.qa.config.QaDiagnosticsProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-0048/0052: the k >= 5 floor of multi-dimensional diagnostics, trends and cross-run matching. */
class DiagnosticsRulesTest {

    @Test
    void kAnonymous_whenFewerThanFiveAccounts_isFalseWhateverKSays() {
        assertThat(DiagnosticsRules.kAnonymous(4, 5)).isFalse();
        assertThat(DiagnosticsRules.kAnonymous(5, 5)).isTrue();
        // A k below the floor (a bug or a hand-made value) cannot open the gate below 5.
        assertThat(DiagnosticsRules.kAnonymous(4, 3)).isFalse();
        assertThat(DiagnosticsRules.kAnonymous(4, 0)).isFalse();
        assertThat(DiagnosticsRules.kAnonymous(9, 10)).isFalse();
        assertThat(DiagnosticsRules.kAnonymous(10, 10)).isTrue();
    }

    @Test
    void properties_whenMinAskersIsBelowFive_refuseToStart() {
        assertThatThrownBy(() -> properties(4)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("min-askers");
        assertThat(properties(5).minAskers()).isEqualTo(5);
        assertThat(properties(7).minAskers()).isEqualTo(7);
    }

    @Test
    void trend_followsTheSizeAgainstThePreviousRun() {
        assertThat(DiagnosticsRules.trend(10, null)).isEqualTo(ClusterTrend.NEW);
        assertThat(DiagnosticsRules.trend(10, 8)).isEqualTo(ClusterTrend.GROWING);
        assertThat(DiagnosticsRules.trend(9, 8)).isEqualTo(ClusterTrend.STABLE);
        assertThat(DiagnosticsRules.trend(8, 10)).isEqualTo(ClusterTrend.SHRINKING);
        assertThat(DiagnosticsRules.trend(9, 10)).isEqualTo(ClusterTrend.STABLE);
    }

    @Test
    void matchPrevious_takesTheMostSimilarPreviousClusterOnce() {
        float[] a = {1, 0, 0};
        float[] b = {0, 1, 0};
        float[] nearA = {0.95f, 0.05f, 0};
        List<Integer> matches = DiagnosticsRules.matchPrevious(List.of(nearA, a, b), List.of(b, a), 0.85);
        // nearA takes the previous "a" (index 1); the current "a" finds it taken; "b" matches index 0.
        assertThat(matches).containsExactly(1, -1, 0);
        assertThat(DiagnosticsRules.matchPrevious(List.of(a), List.of(), 0.85)).containsExactly(-1);
    }

    private static QaDiagnosticsProperties properties(int minAskers) {
        return new QaDiagnosticsProperties(true, "0 30 3 * * *", "Europe/Istanbul", Duration.ofDays(30), minAskers,
                0.8, 0.85, 5000, 2000, 5, 3, Duration.ofDays(180), Duration.ofDays(14), Duration.ofHours(1),
                Duration.ofDays(400), Duration.ofDays(180), Duration.ofDays(30), 400, Duration.ofMinutes(10),
                new QaDiagnosticsProperties.Contradiction(true, 0.9, 2000, 3, 50));
    }
}
