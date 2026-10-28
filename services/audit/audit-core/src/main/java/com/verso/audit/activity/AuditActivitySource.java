package com.verso.audit.activity;

import com.verso.audit.repository.AuditActivityRepository;
import com.verso.audit.repository.AuditActivityRepository.DeletionDay;
import com.verso.platform.core.activity.ActivityCategory;
import com.verso.platform.core.activity.ActivityEntry;
import com.verso.platform.core.activity.ActivityEntry.Actor;
import com.verso.platform.core.activity.ActivityEntry.AuditRef;
import com.verso.platform.core.activity.ActivityEntry.Link;
import com.verso.platform.core.activity.ActivityQuery;
import com.verso.platform.core.activity.ActivitySource;
import com.verso.platform.core.activity.ActivityStatus;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * The audit module's part of the activity centre (ADR-0058): document deletions per day, which only the audit log
 * still remembers (a deleted document's row is gone). Counts only; the actor is "a user" or "a connector sync", never a
 * person. Deletions belong to no collection the viewer could filter by, so a collection filter leaves them out.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AuditActivitySource implements ActivitySource {

    private final AuditActivityRepository deletions;

    AuditActivitySource(AuditActivityRepository deletions) {
        this.deletions = deletions;
    }

    @Override
    public String key() {
        return "audit";
    }

    @Override
    public List<ActivityEntry> entries(ActivityQuery q) {
        if (q.collection() != null) return List.of();
        return deletions.deletions(q.from(), q.to(), q.limit()).stream().map(AuditActivitySource::entry).toList();
    }

    static ActivityEntry entry(DeletionDay d) {
        boolean sync = "CONNECTOR_DOCUMENT_DELETED".equals(d.action());
        UUID id = UUID.nameUUIDFromBytes(("deletions:" + d.day() + ":" + d.action()).getBytes(StandardCharsets.UTF_8));
        return new ActivityEntry(id, ActivityCategory.KNOWLEDGE, "DOCUMENTS_DELETED", ActivityStatus.INFO, d.latest(),
                d.latest(), sync ? Actor.system("Bağlayıcı senkronu") : Actor.role("Kullanıcılar ve yöneticiler"),
                d.count() + " belge silindi", sync ? "Kaynaktan kalkan dosyalar" : "Elle silinen belgeler", null,
                new Link("trust", null), new AuditRef("DOCUMENT", null, d.action()), false);
    }
}
