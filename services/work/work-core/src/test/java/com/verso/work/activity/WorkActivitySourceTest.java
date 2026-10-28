package com.verso.work.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.platform.core.activity.ActivityCategory;
import com.verso.platform.core.activity.ActivityEntry;
import com.verso.platform.core.activity.ActivityEntry.ActorKind;
import com.verso.platform.core.activity.ActivityStatus;
import com.verso.work.repository.WorkActivityRepository.RunDay;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Skill runs in the activity centre (ADR-0058): per day and skill, a role as actor, the run status summed up. */
class WorkActivitySourceTest {

    private static final Instant DAY = Instant.parse("2026-10-28T00:00:00Z");

    @Test
    void entry_summarisesADayOfASkill_withoutAPerson() {
        ActivityEntry e = WorkActivitySource.entry(new RunDay(DAY, "toplanti-notu", "Toplantı notu", 31, 0, 12, 0,
                DAY.plusSeconds(50_000)));
        assertThat(e.category()).isEqualTo(ActivityCategory.RUN);
        assertThat(e.type()).isEqualTo("SKILL_RUN");
        assertThat(e.status()).isEqualTo(ActivityStatus.SUCCEEDED);
        assertThat(e.title()).isEqualTo("Toplantı notu · 31 çalışma");
        assertThat(e.actor().kind()).isEqualTo(ActorKind.ROLE);
        assertThat(e.link().screen()).isEqualTo("beceriler");
        assertThat(e.attention()).isFalse();
        assertThat(WorkActivitySource.MIN_OWNERS).as("k of ADR-0048").isGreaterThanOrEqualTo(5);
    }

    @Test
    void entry_status_followsTheRuns() {
        assertThat(WorkActivitySource.entry(new RunDay(DAY, "s", "S", 5, 2, 0, 0, DAY)).status()).isEqualTo(ActivityStatus.PARTIAL);
        assertThat(WorkActivitySource.entry(new RunDay(DAY, "s", "S", 5, 5, 0, 0, DAY)).status()).isEqualTo(ActivityStatus.FAILED);
        assertThat(WorkActivitySource.entry(new RunDay(DAY, "s", "S", 5, 0, 0, 1, DAY)).status()).isEqualTo(ActivityStatus.RUNNING);
        assertThat(WorkActivitySource.entry(new RunDay(DAY, "s", null, 5, 1, 0, 0, DAY)).title()).startsWith("s · ");
        assertThat(WorkActivitySource.entry(new RunDay(DAY, "s", "S", 5, 1, 0, 0, DAY)).attention()).isTrue();
    }
}
