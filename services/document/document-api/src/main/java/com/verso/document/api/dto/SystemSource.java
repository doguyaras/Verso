package com.verso.document.api.dto;

import com.verso.document.api.enums.SourceUnit;
import java.util.UUID;

/**
 * Where an entity of the system model is mentioned (ADR-0054, MENTIONED_IN): one chunk of a READY document the reader
 * may read (ADR-0028), with the SHA-256 the mention was extracted from. {@code passageId} is the chunk id, readable
 * again through {@link com.verso.document.api.DocumentRetrieval#passage} and MCP verso_fetch. The file name is
 * document data and is never logged.
 *
 * @param defining true when this chunk defines the entity (its expansion, its description, its step list)
 */
public record SystemSource(UUID documentId, String fileName, int pageNumber, SourceUnit unit, UUID passageId,
                           String contentSha256, UUID collectionId, String collectionName, boolean defining) {

    @Override
    public String toString() {
        return "SystemSource[documentId=" + documentId + ", passageId=" + passageId + "]";
    }
}
