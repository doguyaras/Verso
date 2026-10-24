package com.verso.document.api;

import com.verso.document.api.dto.DocumentInventory;
import com.verso.document.api.dto.DocumentSummary;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-process contract for other modules (ADR-0003, ADR-0023): which documents still exist and what they are called.
 * The qa module keeps citation references (document id, page) in its history and resolves them here on read, so a
 * deleted document shows as deleted instead of breaking the history (no cross-schema join, reference 10.1).
 *
 * <p>Names and ids only, never content. The owner's view filters on the account in the query itself; the two
 * account-wide methods serve the admin usage metrics and are refused to every caller without {@code verso-admin}
 * (ADR-0019, ADR-0023), whichever module calls them.
 */
public interface DocumentCatalog {

    /** At most this many ids per call: one conversation's citations, or the documents an installation ever cited. */
    int MAX_IDS = 10_000;

    /**
     * The file names of those {@code documentIds} that are still documents of the account; deleted documents and
     * documents of other accounts are simply absent from the map.
     *
     * @param accountId the validated token's sub (never taken from a request)
     */
    Map<UUID, String> fileNames(String accountId, Collection<UUID> documentIds);

    /** Admin metrics: name, format and creation time of the given documents of any account; deleted ones are absent. */
    List<DocumentSummary> describeAcrossAccounts(Collection<UUID> documentIds);

    /**
     * Admin metrics: READY documents of every account that are not in {@code excluded} (the documents ever cited),
     * oldest first, at most {@code limit}, with the total count.
     */
    DocumentInventory readyDocumentsExcept(Collection<UUID> excluded, int limit);
}
