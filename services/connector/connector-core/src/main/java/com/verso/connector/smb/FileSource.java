package com.verso.connector.smb;

import com.verso.connector.domain.SyncPlanner.ListedFile;
import com.verso.connector.domain.SourcePaths;
import java.time.Instant;
import java.util.List;

/**
 * One open session to a file share (ADR-0038). Read-only by construction: it lists and reads, nothing else.
 * Implementations never follow links and never leave the configured prefix.
 */
public interface FileSource extends AutoCloseable {

    /**
     * Every in-scope file under the prefix.
     *
     * @param complete false when any folder could not be read, a link was met where a folder was expected, or a limit
     *                 cut the walk: then the caller must not treat missing files as deleted
     * @param problem     fixed code of the first problem that made the listing incomplete, else null
     * @param incremental only what changed since a cursor was listed (Confluence, ADR-0044): files not listed are
     *                    unchanged, never deleted
     * @param highWater   the newest modification time listed (the next cursor), null when nothing was listed
     */
    record Listing(List<ListedFile> files, boolean complete, String problem, int unsafeEntries, boolean incremental,
                   Instant highWater) {

        /** A full listing (SMB). */
        public Listing(List<ListedFile> files, boolean complete, String problem, int unsafeEntries) {
            this(files, complete, problem, unsafeEntries, false, null);
        }
    }

    /**
     * @param keepAlive called between folders, so the caller can renew its lease during a long walk
     */
    Listing list(Runnable keepAlive);

    /** The file's bytes; at most {@code maxBytes}, else {@link SourceException} TOO_LARGE. */
    byte[] read(String relativePath, long maxBytes);

    /** The name the document gets in the collection: the file name, or (Confluence) the page title as Markdown. */
    default String documentName(ListedFile file) {
        return SourcePaths.fileName(file.path());
    }

    @Override
    void close();
}
