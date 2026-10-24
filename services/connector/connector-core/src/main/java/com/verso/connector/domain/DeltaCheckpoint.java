package com.verso.connector.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a SharePoint run left each library (ADR-0045): the delta link to continue from and the folder changes it saw.
 * Written in the run's finishing transaction, by the claim holder, and only when the run applied every change it
 * listed (nothing failed, deferred or held back); otherwise the next run replays the same delta from the previous link,
 * which Graph allows and which is idempotent here (an unchanged item is not downloaded again).
 *
 * @param full the listing enumerated every library from scratch: the stored state is replaced, libraries no longer
 *             configured are dropped
 */
public record DeltaCheckpoint(boolean full, List<Drive> drives) {

    /**
     * @param replaceFolders the folders are {@code upserts} alone (a full enumeration); else upserts and removals apply
     *                       to the stored ones
     */
    public record Drive(String driveId, String rootId, String deltaLink, boolean replaceFolders,
                        Map<String, DriveTree.Folder> upserts, Set<String> removed) {
        @Override
        public String toString() {
            return "Drive[folders=" + upserts.size() + ", removed=" + removed.size() + "]";
        }
    }

    /** One library's stored state, read at the start of a run. */
    public record Stored(String driveId, String rootId, String deltaLink, Map<String, DriveTree.Folder> folders) {
        @Override
        public String toString() {
            return "Stored[folders=" + folders.size() + "]";
        }
    }
}
