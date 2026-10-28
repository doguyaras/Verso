package com.verso.connector.activity;

import com.verso.connector.repository.ConnectorActivityRepository;
import com.verso.connector.repository.ConnectorActivityRepository.RunRow;
import com.verso.platform.core.activity.ActivityCategory;
import com.verso.platform.core.activity.ActivityDetail;
import com.verso.platform.core.activity.ActivityEntry;
import com.verso.platform.core.activity.ActivityEntry.Actor;
import com.verso.platform.core.activity.ActivityEntry.AuditRef;
import com.verso.platform.core.activity.ActivityEntry.Link;
import com.verso.platform.core.activity.ActivityQuery;
import com.verso.platform.core.activity.ActivitySource;
import com.verso.platform.core.activity.ActivityStatus;
import com.verso.platform.core.activity.ActivityViewer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * The connector module's part of the activity centre (ADR-0058): every sync run of the window, and a pending bulk
 * deletion per connector whose deletion safety held files back (an admin confirms it on the connector screen). A run is
 * a system action: its actor is the sync component, never the admin who created the connector.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ConnectorActivitySource implements ActivitySource {

    private static final Actor SYNC = Actor.system("Bağlayıcı senkronu");
    private static final Actor ADMINS = Actor.role("Yönetici rolü");

    private final ConnectorActivityRepository runs;

    ConnectorActivitySource(ConnectorActivityRepository runs) {
        this.runs = runs;
    }

    @Override
    public String key() {
        return "connector";
    }

    @Override
    public List<ActivityEntry> entries(ActivityQuery q) {
        List<ActivityEntry> all = new ArrayList<>();
        for (RunRow r : runs.runs(q.from(), q.to(), q.limit())) {
            if (q.admits(r.collectionId())) all.add(run(r));
        }
        for (RunRow r : runs.heldDeletions(q.to(), q.limit())) {
            if (q.admits(r.collectionId())) all.add(heldDeletion(r));
        }
        all.sort(Comparator.comparing(ActivityEntry::startedAt).reversed());
        return all.subList(0, Math.min(all.size(), q.limit()));
    }

    @Override
    public Optional<ActivityDetail> detail(UUID id, ActivityViewer viewer) {
        return runs.run(id).map(r -> new ActivityDetail(run(r), List.of(
                fact("Bağlayıcı", r.connectorName() + " (" + typeLabel(r.connectorType()) + ")"),
                fact("Listeleme", "INCREMENTAL".equals(r.listingMode()) ? "Artımlı" : "Tam"),
                fact("Hata kodu", r.errorCode() == null ? "—" : r.errorCode())),
                List.of(step("Listelendi", r.listed(), ActivityStatus.SUCCEEDED),
                        step("Eklendi", r.added(), ActivityStatus.SUCCEEDED),
                        step("Güncellendi", r.updated(), ActivityStatus.SUCCEEDED),
                        step("Silindi", r.deleted(), ActivityStatus.SUCCEEDED),
                        step("Değişmedi", r.unchanged(), ActivityStatus.INFO),
                        step("Atlandı", r.skipped(), ActivityStatus.INFO),
                        step("Ertelendi", r.deferred(), r.deferred() > 0 ? ActivityStatus.PENDING : ActivityStatus.INFO),
                        step("Hata", r.failed(), r.failed() > 0 ? ActivityStatus.FAILED : ActivityStatus.INFO)),
                List.of(), List.of()));
    }

    static ActivityEntry run(RunRow r) {
        ActivityStatus status = switch (r.status()) {
            case "RUNNING" -> ActivityStatus.RUNNING;
            case "SUCCEEDED" -> ActivityStatus.SUCCEEDED;
            case "PARTIAL" -> ActivityStatus.PARTIAL;
            default -> ActivityStatus.FAILED; // FAILED and ABANDONED
        };
        String detail = status == ActivityStatus.FAILED && r.errorCode() != null
                ? "Hata: " + r.errorCode()
                : r.added() + " eklendi · " + r.updated() + " güncellendi · " + r.deleted() + " silindi"
                + (r.failed() > 0 ? " · " + r.failed() + " hata" : "");
        return new ActivityEntry(r.id(), ActivityCategory.BACKGROUND, "CONNECTOR_SYNC", status, r.startedAt(),
                r.finishedAt(), SYNC, "Bağlayıcı senkronu · " + r.connectorName(), detail, r.collectionId(),
                new Link("connectors", r.connectorId()), new AuditRef("CONNECTOR", r.connectorId(),
                "CONNECTOR_SYNC_FINISHED"), status == ActivityStatus.FAILED || status == ActivityStatus.PARTIAL);
    }

    static ActivityEntry heldDeletion(RunRow r) {
        // A name-based id: one pending decision per connector and run, stable across requests.
        UUID id = UUID.nameUUIDFromBytes(("connector-held-deletion:" + r.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new ActivityEntry(id, ActivityCategory.APPROVAL, "BULK_DELETION", ActivityStatus.PENDING,
                r.startedAt(), null, ADMINS, "Toplu silme onayı · " + r.connectorName(),
                r.pendingDeletions() + " belge kaynaktan kalktı; silme güvenliği onay bekliyor", r.collectionId(),
                new Link("connectors", r.connectorId()), new AuditRef("CONNECTOR", r.connectorId(), null), true);
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "SMB" -> "dosya paylaşımı";
            case "CONFLUENCE" -> "Confluence";
            case "SHAREPOINT" -> "SharePoint";
            default -> type;
        };
    }

    private static ActivityDetail.Fact fact(String label, String value) {
        return new ActivityDetail.Fact(label, value);
    }

    private static ActivityDetail.Step step(String label, int count, ActivityStatus status) {
        return new ActivityDetail.Step(label, status, String.valueOf(count), null);
    }
}
