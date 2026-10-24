package com.verso.qa.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** The history and metrics settings fail closed at start-up (ADR-0023). */
class HistoryPropertiesTest {

    @Test
    void metrics_whenKIsBelowThree_doesNotStart() {
        assertThatThrownBy(() -> new UsageMetricsProperties("Europe/Istanbul", true, false, 2, 20, 366))
                .hasMessageContaining("at least 3");
        assertThat(new UsageMetricsProperties("Europe/Istanbul", true, false, 5, 20, 366).unansweredMinUsers()).isEqualTo(5);
    }

    @Test
    void metrics_whenTheTimeZoneIsUnknown_doesNotStart() {
        assertThatThrownBy(() -> new UsageMetricsProperties("Mars/Olympus", false, false, 3, 20, 366))
                .hasMessageContaining("time zone");
        assertThat(new UsageMetricsProperties("Europe/Istanbul", false, false, 3, 20, 366).zone())
                .isEqualTo(ZoneId.of("Europe/Istanbul"));
    }

    @Test
    void history_whenRetentionIsOutOfRange_doesNotStart() {
        assertThatThrownBy(() -> new QaHistoryProperties(0, true, Duration.ofHours(1), Duration.ofMinutes(5), 500, 200))
                .hasMessageContaining("retention-days");
        assertThatThrownBy(() -> new QaHistoryProperties(3651, true, Duration.ofHours(1), Duration.ofMinutes(5), 500, 200))
                .hasMessageContaining("retention-days");
        assertThat(new QaHistoryProperties(90, true, Duration.ofHours(1), Duration.ofMinutes(5), 500, 200).retentionDays()).isEqualTo(90);
    }

    @Test
    void database_whenPrinted_neverShowsThePassword() {
        QaDatabaseProperties properties = new QaDatabaseProperties("jdbc:postgresql://db/verso", "svc_qa",
                "secret-value-x", 4, 1, Duration.ofSeconds(5), Duration.ofMinutes(2));
        assertThat(properties.toString()).doesNotContain("secret-value-x");
        assertThat(new QaFlywayProperties("jdbc:postgresql://db/verso", "svc_qa_migrate", "secret-value-y",
                "classpath:db/migration/qa", "qa").toString()).doesNotContain("secret-value-y");
        assertThatThrownBy(() -> new QaDatabaseProperties("jdbc:postgresql://db/verso", "svc_qa", "", 4, 1,
                Duration.ofSeconds(5), Duration.ofMinutes(2))).hasMessageContaining("password");
    }
}
