package com.verso.connector.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.connector.repository.ConnectorActivityRepository.RunRow;
import com.verso.platform.core.activity.ActivityCategory;
import com.verso.platform.core.activity.ActivityEntry;
import com.verso.platform.core.activity.ActivityEntry.ActorKind;
import com.verso.platform.core.activity.ActivityStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A sync run as an activity entry (ADR-0058): status words, attention, a system actor, no address or account. */
class ConnectorActivitySourceTest {

    private static RunRow run(String status, String error, int failed, Integer pending) {
        return new RunRow(UUID.randomUUID(), UUID.randomUUID(), "Kalite", "SHAREPOINT", UUID.randomUUID(), status, error,
                Instant.parse("2026-10-28T14:05:00Z"), Instant.parse("2026-10-28T14:06:00Z"), 10, 2, 1, 0, 7, failed, 0, 0,
                "FULL", pending, false);
    }

    @Test
    void run_mapsEveryStoredStatus_andFlagsFailuresForAttention() {
        assertThat(ConnectorActivitySource.run(run("SUCCEEDED", null, 0, null)).status()).isEqualTo(ActivityStatus.SUCCEEDED);
        assertThat(ConnectorActivitySource.run(run("RUNNING", null, 0, null)).status()).isEqualTo(ActivityStatus.RUNNING);
        ActivityEntry partial = ConnectorActivitySource.run(run("PARTIAL", null, 2, null));
        assertThat(partial.status()).isEqualTo(ActivityStatus.PARTIAL);
        assertThat(partial.attention()).isTrue();
        assertThat(partial.detail()).isEqualTo("2 eklendi · 1 güncellendi · 0 silindi · 2 hata");
        for (String failed : new String[] {"FAILED", "ABANDONED"}) {
            ActivityEntry e = ConnectorActivitySource.run(run(failed, "AUTH_FAILED", 0, null));
            assertThat(e.status()).isEqualTo(ActivityStatus.FAILED);
            assertThat(e.detail()).isEqualTo("Hata: AUTH_FAILED");
            assertThat(e.attention()).isTrue();
        }
    }

    @Test
    void entries_carryAConnectorNameAndASystemActor_never_aPerson() {
        RunRow row = run("SUCCEEDED", null, 0, null);
        ActivityEntry e = ConnectorActivitySource.run(row);
        assertThat(e.category()).isEqualTo(ActivityCategory.BACKGROUND);
        assertThat(e.actor().kind()).isEqualTo(ActorKind.SYSTEM);
        assertThat(e.title()).isEqualTo("Bağlayıcı senkronu · Kalite");
        assertThat(e.link().targetId()).isEqualTo(row.connectorId());
        assertThat(e.audit().targetType()).isEqualTo("CONNECTOR");
        assertThat(e.collectionId()).isEqualTo(row.collectionId());
    }

    @Test
    void heldDeletion_isAPendingApproval_withAStableId() {
        RunRow row = run("SUCCEEDED", null, 0, 214);
        ActivityEntry first = ConnectorActivitySource.heldDeletion(row);
        assertThat(first.category()).isEqualTo(ActivityCategory.APPROVAL);
        assertThat(first.type()).isEqualTo("BULK_DELETION");
        assertThat(first.status()).isEqualTo(ActivityStatus.PENDING);
        assertThat(first.detail()).startsWith("214 belge");
        assertThat(ConnectorActivitySource.heldDeletion(row).id()).isEqualTo(first.id());
        assertThat(first.actor().kind()).isEqualTo(ActorKind.ROLE);
    }
}
