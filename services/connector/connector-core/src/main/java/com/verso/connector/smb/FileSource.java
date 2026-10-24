package com.verso.connector.smb;

import com.verso.connector.domain.SyncPlanner.ListedFile;
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
     * @param problem  fixed code of the first problem that made the listing incomplete, else null
     */
    record Listing(List<ListedFile> files, boolean complete, String problem, int unsafeEntries) {
    }

    /**
     * @param keepAlive called between folders, so the caller can renew its lease during a long walk
     */
    Listing list(Runnable keepAlive);

    /** The file's bytes; at most {@code maxBytes}, else {@link SourceException} TOO_LARGE. */
    byte[] read(String relativePath, long maxBytes);

    @Override
    void close();
}
