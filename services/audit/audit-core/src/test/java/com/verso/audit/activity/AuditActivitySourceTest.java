package com.verso.audit.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.audit.repository.AuditActivityRepository.DeletionDay;
import com.verso.platform.core.activity.ActivityCategory;
import com.verso.platform.core.activity.ActivityEntry;
import com.verso.platform.core.activity.ActivityEntry.ActorKind;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Deletions from the audit log (ADR-0058): counts per day, a role or the sync as actor, never an account. */
class AuditActivitySourceTest {

    private static final Instant DAY = Instant.parse("2026-10-27T00:00:00Z");

    @Test
    void deletionsByPeople_areCountedUnderARole() {
        ActivityEntry e = AuditActivitySource.entry(new DeletionDay(DAY, "DOCUMENT_DELETED", 3, DAY.plusSeconds(60)));
        assertThat(e.category()).isEqualTo(ActivityCategory.KNOWLEDGE);
        assertThat(e.type()).isEqualTo("DOCUMENTS_DELETED");
        assertThat(e.title()).isEqualTo("3 belge silindi");
        assertThat(e.actor().kind()).isEqualTo(ActorKind.ROLE);
        assertThat(e.audit().action()).isEqualTo("DOCUMENT_DELETED");
        assertThat(e.audit().targetId()).isNull();
    }

    @Test
    void deletionsBySync_areASystemAction_withAnIdOtherThanThePeoplesOne() {
        ActivityEntry sync = AuditActivitySource.entry(new DeletionDay(DAY, "CONNECTOR_DOCUMENT_DELETED", 214, DAY));
        ActivityEntry people = AuditActivitySource.entry(new DeletionDay(DAY, "DOCUMENT_DELETED", 214, DAY));
        assertThat(sync.actor().kind()).isEqualTo(ActorKind.SYSTEM);
        assertThat(sync.id()).isNotEqualTo(people.id());
    }
}
