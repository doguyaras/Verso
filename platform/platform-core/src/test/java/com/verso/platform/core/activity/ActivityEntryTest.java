package com.verso.platform.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.platform.core.activity.ActivityEntry.Actor;
import com.verso.platform.core.activity.ActivityEntry.AuditRef;
import com.verso.platform.core.activity.ActivityEntry.Link;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The activity SPI's own guards (ADR-0058): fixed words, bounded one-line text, a viewer that never prints. */
class ActivityEntryTest {

    private static final Instant AT = Instant.parse("2026-10-28T10:00:00Z");

    private static ActivityEntry entry(String type, String title, String screen) {
        return new ActivityEntry(UUID.randomUUID(), ActivityCategory.HOOK, type, ActivityStatus.INFO, AT, null,
                Actor.system("Kanca"), title, null, null, screen == null ? null : new Link(screen, null), null, false);
    }

    @Test
    void entry_keepsTextOnOneBoundedLine() {
        ActivityEntry e = entry("HOOK_ACTION", "a\nb‮c" + "x".repeat(400), "health");
        assertThat(e.title()).doesNotContain("\n").doesNotContain("‮").hasSize(ActivityEntry.MAX_TITLE).endsWith("…");
        assertThat(new Actor(ActivityEntry.ActorKind.TEAM, "y".repeat(200)).label()).hasSize(80);
    }

    @Test
    void entry_refusesWordsThatAreNotFixed() {
        assertThatThrownBy(() -> entry("hook action", "t", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry("HOOK_ACTION", "t", "../admin")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditRef("doc", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditRef("DOCUMENT", null, "deleted")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new AuditRef("DOCUMENT", null, "DOCUMENT_DELETED").action()).isEqualTo("DOCUMENT_DELETED");
    }

    @Test
    void query_boundsTheWindowAndTheLimit() {
        ActivityViewer viewer = new ActivityViewer("sub-1", List.of("g"));
        assertThatThrownBy(() -> new ActivityQuery(AT, AT, null, 10, viewer)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActivityQuery(AT, AT.plusSeconds(1), null, ActivityQuery.MAX_LIMIT + 1, viewer))
                .isInstanceOf(IllegalArgumentException.class);
        UUID collection = UUID.randomUUID();
        ActivityQuery q = new ActivityQuery(AT, AT.plusSeconds(60), collection, 5, viewer);
        assertThat(q.admits(collection)).isTrue();
        assertThat(q.admits(null)).isFalse();
        assertThat(q.contains(AT)).isTrue();
        assertThat(q.contains(AT.plusSeconds(60))).isFalse();
        assertThat(viewer.toString()).doesNotContain("sub-1").doesNotContain("g]");
    }

    @Test
    void detail_dropsTheNameOfAnUnreadableSource() {
        assertThat(new ActivityDetail.Source("gizli.pdf", "PDF", false, 2).name()).isNull();
        assertThat(new ActivityDetail.Source("acik.pdf", "PDF", true, 2).name()).isEqualTo("acik.pdf");
    }
}
