package com.verso.document.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.repository.DocumentActivityRepository.DayChange;
import com.verso.document.repository.DocumentActivityRepository.ExtractionDay;
import com.verso.platform.core.activity.ActivityCategory;
import com.verso.platform.core.activity.ActivityEntry;
import com.verso.platform.core.activity.ActivityEntry.ActorKind;
import com.verso.platform.core.activity.ActivityStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Knowledge changes as activity entries (ADR-0058): the collection is the actor, ids are stable per day. */
class DocumentActivitySourceTest {

    private static final Instant DAY = Instant.parse("2026-10-28T00:00:00Z");

    @Test
    void change_summarisesADayOfACollection_withTheCollectionAsTheActor() {
        UUID collection = UUID.randomUUID();
        DayChange c = new DayChange(DAY, collection, "BT destek", 12, 3, 0, DAY.plusSeconds(33_300),
                List.of("vpn.pdf", "yazici.docx"));
        ActivityEntry e = DocumentActivitySource.change(c);
        assertThat(e.category()).isEqualTo(ActivityCategory.KNOWLEDGE);
        assertThat(e.type()).isEqualTo("DOCUMENTS_ADDED");
        assertThat(e.title()).isEqualTo("12 belge eklendi, 3 belge değişti");
        assertThat(e.detail()).isEqualTo("Alan: BT destek · vpn.pdf, yazici.docx");
        assertThat(e.actor().kind()).isEqualTo(ActorKind.TEAM);
        assertThat(e.actor().label()).isEqualTo("BT destek alanı");
        assertThat(e.collectionId()).isEqualTo(collection);
        assertThat(DocumentActivitySource.change(c).id()).as("stable id").isEqualTo(e.id());
    }

    @Test
    void change_withFailuresIsPartial_andOnlyChangedIsChanged() {
        ActivityEntry e = DocumentActivitySource.change(new DayChange(DAY, UUID.randomUUID(), "Kalite", 0, 2, 1, DAY,
                List.of()));
        assertThat(e.type()).isEqualTo("DOCUMENTS_CHANGED");
        assertThat(e.status()).isEqualTo(ActivityStatus.PARTIAL);
        assertThat(e.title()).isEqualTo("2 belge değişti");
        assertThat(e.detail()).isEqualTo("Alan: Kalite · 1 işlenemedi");
    }

    @Test
    void extraction_isABackgroundRunOfTheSystemModel() {
        ActivityEntry e = DocumentActivitySource.extraction(new ExtractionDay(DAY, 4, 14, 3, DAY.plusSeconds(4200)));
        assertThat(e.category()).isEqualTo(ActivityCategory.BACKGROUND);
        assertThat(e.type()).isEqualTo("SYSTEM_MODEL");
        assertThat(e.detail()).isEqualTo("4 belge · 14 varlık · 3 ilişki");
        assertThat(e.actor().kind()).isEqualTo(ActorKind.SYSTEM);
    }
}
