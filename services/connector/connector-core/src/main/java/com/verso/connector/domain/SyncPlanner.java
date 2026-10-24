package com.verso.connector.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The incremental diff of one sync (ADR-0038), a pure function so the rules are unit-tested without a file server:
 * what the source lists now against what the connector tracked before.
 *
 * <ul>
 *   <li>Listed, not tracked: <b>new</b>, fetched.</li>
 *   <li>Listed, tracked, same size, modification time and file id: <b>unchanged</b>, not even downloaded; a file that
 *       failed before is retried once its backoff has passed, a skipped one stays skipped.</li>
 *   <li>Listed, tracked, anything of the three differs: <b>changed</b>, fetched; the content hash decides later whether
 *       the document must be replaced (a touched but identical file is not re-ingested).</li>
 *   <li>Tracked, its document gone from Verso (someone deleted or moved it): fetched again, the source wins.</li>
 *   <li>Tracked, not listed: <b>deleted</b> at the source, but only when the listing was complete: an unreadable folder
 *       or a cut listing must never read as "everything inside was deleted".</li>
 * </ul>
 */
public final class SyncPlanner {

    private SyncPlanner() {}

    /** A file as the source lists it now. */
    public record ListedFile(String path, long fileId, long sizeBytes, Instant modifiedAt) {
        public ListedFile {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(modifiedAt, "modifiedAt");
        }
    }

    /** What the connector remembers about a file. */
    public record KnownItem(String path, long fileId, long sizeBytes, Instant modifiedAt, String contentSha256,
                            UUID documentId, State state, Instant nextAttemptAt, int attempts) {

        public enum State { SYNCED, FAILED, SKIPPED }

        boolean sameVersion(ListedFile file) {
            return fileId == file.fileId() && sizeBytes == file.sizeBytes() && modifiedAt.equals(file.modifiedAt());
        }
    }

    /** Why a file is fetched. */
    public enum Reason { NEW, CHANGED, RETRY, RESTORE }

    /** One file to download, with what was known about it (null for a new file). */
    public record Fetch(ListedFile file, KnownItem known, Reason reason) {
    }

    /**
     * The plan of one run.
     *
     * @param fetch     files to download, new files first, then changes, retries and restores, each in path order
     * @param delete    tracked files the complete listing no longer has
     * @param unchanged files that need nothing
     * @param waiting   failed files whose backoff has not passed yet
     */
    public record Plan(List<Fetch> fetch, List<KnownItem> delete, int unchanged, int waiting) {
    }

    /**
     * @param listed          the source's files now (already filtered by globs and candidate extensions)
     * @param known           the tracked files by path
     * @param missingDocuments ids of tracked documents that are no longer in the target collection
     * @param listingComplete whether the listing covered the whole source
     */
    public static Plan plan(Collection<ListedFile> listed, Map<String, KnownItem> known, Set<UUID> missingDocuments,
                            boolean listingComplete, Instant now) {
        List<Fetch> fresh = new ArrayList<>();
        List<Fetch> changed = new ArrayList<>();
        List<Fetch> retries = new ArrayList<>();
        List<Fetch> restores = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int unchanged = 0;
        int waiting = 0;
        for (ListedFile file : listed.stream().sorted((a, b) -> a.path().compareTo(b.path())).toList()) {
            if (!seen.add(file.path())) continue;
            KnownItem item = known.get(file.path());
            if (item == null) {
                fresh.add(new Fetch(file, null, Reason.NEW));
            } else if (!item.sameVersion(file)) {
                changed.add(new Fetch(file, item, Reason.CHANGED));
            } else if (item.state() == KnownItem.State.FAILED) {
                if (item.nextAttemptAt() == null || !item.nextAttemptAt().isAfter(now)) {
                    retries.add(new Fetch(file, item, Reason.RETRY));
                } else {
                    waiting++;
                }
            } else if (item.state() == KnownItem.State.SYNCED && item.documentId() != null
                    && missingDocuments.contains(item.documentId())) {
                restores.add(new Fetch(file, item, Reason.RESTORE));
            } else {
                unchanged++;
            }
        }
        List<KnownItem> delete = new ArrayList<>();
        if (listingComplete) {
            known.values().stream().filter(item -> !seen.contains(item.path()))
                    .sorted((a, b) -> a.path().compareTo(b.path())).forEach(delete::add);
        }
        List<Fetch> fetch = new ArrayList<>(fresh);
        fetch.addAll(changed);
        fetch.addAll(retries);
        fetch.addAll(restores);
        return new Plan(List.copyOf(fetch), List.copyOf(delete), unchanged, waiting);
    }

    /**
     * Deletion safety (ADR-0038; the research's "silme emniyeti"): when a run would delete more than {@code percent} of
     * the tracked files and more than {@code minimum} of them, it deletes nothing unless an admin confirmed it for this
     * run. A renamed folder, a lost permission or a wrong prefix then cannot empty a collection on its own.
     */
    public static boolean deletionAllowed(int deletions, int tracked, int percent, int minimum, boolean confirmed) {
        if (confirmed || deletions <= minimum || percent >= 100) return true;
        return (long) deletions * 100 <= (long) percent * tracked;
    }

    /** Backoff of a failed file: base · 2^(attempts-1), at most max. */
    public static java.time.Duration backoff(int attempts, java.time.Duration base, java.time.Duration max) {
        int shift = Math.max(0, Math.min(attempts - 1, 20));
        java.time.Duration wait = base.multipliedBy(1L << shift);
        return wait.compareTo(max) > 0 ? max : wait;
    }
}
