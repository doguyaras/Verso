package com.verso.connector.repository;

import com.verso.connector.domain.DeltaCheckpoint;
import com.verso.connector.domain.DriveTree;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * connector.sharepoint_drive and connector.sharepoint_folder (ADR-0045): per library, the delta link a run continues
 * from and the folder tree its paths are built from. Written only by {@link #apply}, inside the run's finishing
 * transaction and after the claim holder's result was stored (the connector row is locked by that update until commit),
 * so two instances never interleave their checkpoints. Folder names are kept for paths only; the delta link is opaque
 * Graph state (no credential) and never logged.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SharePointStateRepository {

    /** Folders per statement (arrays of this size go through unnest). */
    private static final int CHUNK = 5_000;

    private final ConnectorDatabase db;

    public SharePointStateRepository(ConnectorDatabase db) {
        this.db = db;
    }

    /** The stored libraries with their folders; empty when the connector has none (list from scratch). */
    public List<DeltaCheckpoint.Stored> load(UUID connectorId) {
        record DriveRow(String driveId, String rootId, String deltaLink) {
        }
        List<DriveRow> drives = db.jdbc().sql("SELECT drive_id, root_id, delta_link FROM connector.sharepoint_drive "
                        + "WHERE connector_id = :c ORDER BY drive_id")
                .param("c", connectorId)
                .query((rs, n) -> new DriveRow(rs.getString("drive_id"), rs.getString("root_id"), rs.getString("delta_link")))
                .list();
        Map<String, Map<String, DriveTree.Folder>> folders = new HashMap<>();
        db.jdbc().sql("SELECT drive_id, item_id, parent_id, name FROM connector.sharepoint_folder WHERE connector_id = :c")
                .param("c", connectorId)
                .query((RowCallbackHandler) rs -> folders.computeIfAbsent(rs.getString("drive_id"), k -> new HashMap<>())
                        .put(rs.getString("item_id"), new DriveTree.Folder(rs.getString("parent_id"), rs.getString("name"))));
        List<DeltaCheckpoint.Stored> stored = new ArrayList<>();
        for (DriveRow d : drives) {
            stored.add(new DeltaCheckpoint.Stored(d.driveId(), d.rootId(), d.deltaLink(),
                    Map.copyOf(folders.getOrDefault(d.driveId(), Map.of()))));
        }
        return stored;
    }

    /**
     * Stores a run's checkpoint; the caller's transaction makes it atomic with the run's result. A full listing replaces
     * every library of the connector (one that is no longer configured goes, its folders by cascade).
     */
    public void apply(UUID connectorId, DeltaCheckpoint checkpoint) {
        if (checkpoint.full()) {
            db.jdbc().sql("DELETE FROM connector.sharepoint_drive WHERE connector_id = :c").param("c", connectorId).update();
        }
        for (DeltaCheckpoint.Drive drive : checkpoint.drives()) {
            db.jdbc().sql("INSERT INTO connector.sharepoint_drive (connector_id, drive_id, root_id, delta_link, updated_at) "
                            + "VALUES (:c, :d, :r, :l, now()) ON CONFLICT (connector_id, drive_id) DO UPDATE SET "
                            + "root_id = EXCLUDED.root_id, delta_link = EXCLUDED.delta_link, updated_at = now()")
                    .param("c", connectorId).param("d", drive.driveId()).param("r", drive.rootId())
                    .param("l", drive.deltaLink()).update();
            if (drive.replaceFolders()) {
                db.jdbc().sql("DELETE FROM connector.sharepoint_folder WHERE connector_id = :c AND drive_id = :d")
                        .param("c", connectorId).param("d", drive.driveId()).update();
            }
            List<String> removed = List.copyOf(drive.removed());
            for (int i = 0; i < removed.size(); i += CHUNK) {
                List<String> part = removed.subList(i, Math.min(removed.size(), i + CHUNK));
                db.jdbc().sql("DELETE FROM connector.sharepoint_folder WHERE connector_id = :c AND drive_id = :d "
                                + "AND item_id = ANY(CAST(:ids AS text[]))")
                        .param("c", connectorId).param("d", drive.driveId())
                        .param("ids", part.toArray(String[]::new)).update();
            }
            List<Map.Entry<String, DriveTree.Folder>> upserts = List.copyOf(drive.upserts().entrySet());
            for (int i = 0; i < upserts.size(); i += CHUNK) {
                List<Map.Entry<String, DriveTree.Folder>> part = upserts.subList(i, Math.min(upserts.size(), i + CHUNK));
                db.jdbc().sql("INSERT INTO connector.sharepoint_folder (connector_id, drive_id, item_id, parent_id, name) "
                                + "SELECT :c, :d, t.i, t.p, t.n FROM unnest(CAST(:ids AS text[]), CAST(:parents AS text[]), "
                                + "CAST(:names AS text[])) AS t(i, p, n) "
                                + "ON CONFLICT (connector_id, drive_id, item_id) DO UPDATE SET "
                                + "parent_id = EXCLUDED.parent_id, name = EXCLUDED.name")
                        .param("c", connectorId).param("d", drive.driveId())
                        .param("ids", part.stream().map(Map.Entry::getKey).toArray(String[]::new))
                        .param("parents", part.stream().map(e -> e.getValue().parentId()).toArray(String[]::new))
                        .param("names", part.stream().map(e -> e.getValue().name()).toArray(String[]::new))
                        .update();
            }
        }
    }
}
