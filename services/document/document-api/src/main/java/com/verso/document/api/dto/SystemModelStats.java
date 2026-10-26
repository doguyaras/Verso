package com.verso.document.api.dto;

import com.verso.document.api.enums.SystemEntityType;
import java.util.Map;

/**
 * The size of the system model over the documents the reader may read (ADR-0054).
 *
 * @param entities          distinct entities (type and key) per type
 * @param documents         readable READY documents with an extracted model
 * @param pendingDocuments  readable READY documents still waiting for extraction (the backfill job takes them)
 * @param extractorVersion  the running extractor's version; a model of another version is extracted again
 * @param enrichmentEnabled whether retrieval adds defining chunks of known entities (off until measured)
 */
public record SystemModelStats(Map<SystemEntityType, Long> entities, long documents, long pendingDocuments,
                               int extractorVersion, boolean enrichmentEnabled) {

    public SystemModelStats {
        entities = Map.copyOf(entities);
    }
}
