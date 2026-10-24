package com.verso.document.api;

import com.verso.document.api.dto.DocumentAccess;
import com.verso.document.api.dto.DocumentInventory;
import com.verso.document.api.dto.DocumentSummary;
import com.verso.document.api.dto.Reader;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-process contract for other modules (ADR-0003, ADR-0023): which documents still exist, whether a reader may still
 * read them and what they are called. The qa module keeps citation references (document id, page) in its history and
 * resolves them here on read, so a deleted document shows as deleted and a document the reader lost access to shows as
 * not accessible, instead of breaking the history (no cross-schema join, reference 10.1).
 *
 * <p>Names and ids only, never content. The reader's view filters on the reader's principals in the query itself
 * (ADR-0028); the two account-wide methods serve the admin usage metrics and are refused to every caller without
 * {@code verso-admin} (ADR-0019, ADR-0023), whichever module calls them.
 */
public interface DocumentCatalog {

    /** At most this many ids per call: one conversation's citations, or the documents an installation ever cited. */
    int MAX_IDS = 10_000;

    /**
     * The state of each of {@code documentIds} for this reader: accessible with its name and collection, or existing
     * but no longer accessible (no name). Deleted documents are absent from the map.
     *
     * @param reader the validated token's sub and groups (never taken from a request)
     */
    Map<UUID, DocumentAccess> access(Reader reader, Collection<UUID> documentIds);

    /**
     * The file names of those {@code documentIds} the account may read without any group: its personal documents and
     * the collections granted to its sub. Deleted and inaccessible documents are absent.
     *
     * @param accountId the validated token's sub (never taken from a request)
     */
    default Map<UUID, String> fileNames(String accountId, Collection<UUID> documentIds) {
        Map<UUID, String> names = new LinkedHashMap<>();
        access(Reader.personal(accountId), documentIds).forEach((id, access) -> {
            if (access.accessible()) names.put(id, access.fileName());
        });
        return names;
    }

    /** Admin metrics: name, format and creation time of the given documents of any account; deleted ones are absent. */
    List<DocumentSummary> describeAcrossAccounts(Collection<UUID> documentIds);

    /**
     * Admin metrics: READY documents of every account that are not in {@code excluded} (the documents ever cited),
     * oldest first, at most {@code limit}, with the total count.
     */
    DocumentInventory readyDocumentsExcept(Collection<UUID> excluded, int limit);
}
