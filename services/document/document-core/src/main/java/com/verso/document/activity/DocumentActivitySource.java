package com.verso.document.activity;

import com.verso.document.api.dto.Reader;
import com.verso.document.repository.DocumentActivityRepository;
import com.verso.document.repository.DocumentActivityRepository.DayChange;
import com.verso.document.repository.DocumentActivityRepository.ExtractionDay;
import com.verso.platform.core.activity.ActivityCategory;
import com.verso.platform.core.activity.ActivityEntry;
import com.verso.platform.core.activity.ActivityEntry.Actor;
import com.verso.platform.core.activity.ActivityEntry.AuditRef;
import com.verso.platform.core.activity.ActivityEntry.Link;
import com.verso.platform.core.activity.ActivityQuery;
import com.verso.platform.core.activity.ActivitySource;
import com.verso.platform.core.activity.ActivityStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * The document module's part of the activity centre (ADR-0058): per day and shared collection, how many documents were
 * added or changed, and the system-model extraction of the day (ADR-0054). Everything is filtered with the viewer's
 * principals (ADR-0028): a collection the admin has no grant on does not appear. The actor is the collection (a team's
 * area), never the uploader. Deletions are in the audit log; the audit module reports them.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class DocumentActivitySource implements ActivitySource {

    private static final Actor EXTRACTOR = Actor.system("Sistem modeli çıkarımı");

    private final DocumentActivityRepository documents;

    DocumentActivitySource(DocumentActivityRepository documents) {
        this.documents = documents;
    }

    @Override
    public String key() {
        return "document";
    }

    @Override
    public List<ActivityEntry> entries(ActivityQuery q) {
        String[] principals = new Reader(q.viewer().accountId(), q.viewer().groups()).principals()
                .toArray(String[]::new);
        List<ActivityEntry> all = new ArrayList<>();
        for (DayChange c : documents.changes(principals, q.from(), q.to(), q.collection(), q.limit())) {
            all.add(change(c));
        }
        if (q.collection() == null) {
            for (ExtractionDay e : documents.extractions(principals, q.from(), q.to(), q.limit())) all.add(extraction(e));
        }
        all.sort(Comparator.comparing(ActivityEntry::startedAt).reversed());
        return all.subList(0, Math.min(all.size(), q.limit()));
    }

    static ActivityEntry change(DayChange c) {
        String title = c.added() > 0 && c.changed() > 0 ? c.added() + " belge eklendi, " + c.changed() + " belge değişti"
                : c.added() > 0 ? c.added() + " belge eklendi" : c.changed() + " belge değişti";
        String detail = "Alan: " + c.collectionName() + (c.sampleNames().isEmpty() ? ""
                : " · " + String.join(", ", c.sampleNames())) + (c.failed() > 0 ? " · " + c.failed() + " işlenemedi" : "");
        String type = c.added() > 0 ? "DOCUMENTS_ADDED" : "DOCUMENTS_CHANGED";
        return new ActivityEntry(id("documents:" + c.day() + ":" + c.collectionId()), ActivityCategory.KNOWLEDGE, type,
                c.failed() > 0 ? ActivityStatus.PARTIAL : ActivityStatus.SUCCEEDED, c.latest(), c.latest(),
                Actor.team(c.collectionName() + " alanı"), title, detail, c.collectionId(),
                new Link("documents", c.collectionId()), new AuditRef("COLLECTION", c.collectionId(),
                "DOCUMENT_UPLOADED"), false);
    }

    static ActivityEntry extraction(ExtractionDay e) {
        return new ActivityEntry(id("system-model:" + e.day()), ActivityCategory.BACKGROUND, "SYSTEM_MODEL",
                ActivityStatus.SUCCEEDED, e.latest(), e.latest(), EXTRACTOR, "Sistem modeli çıkarımı",
                e.documents() + " belge · " + e.entities() + " varlık · " + e.relations() + " ilişki", null,
                new Link("systemmap", null), new AuditRef("SYSTEM_MODEL", null, null), false);
    }

    /** Daily aggregates have no row of their own: a name-based id, stable for the same day and collection. */
    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
