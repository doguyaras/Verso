package com.verso.document.api.dto;

import com.verso.document.api.enums.SystemEntityType;
import java.util.List;
import java.util.UUID;

/**
 * A knowledge-health signal of the system model (ADR-0054, read by the ADR-0052 report): computed over the shared
 * documents the admin may read, never over personal documents.
 *
 * @param documentIds the documents behind the signal (bounded)
 * @param values      for TERM_CONFLICT the differing definitions; empty otherwise
 */
public record SystemModelSignal(Kind kind, SystemEntityType type, String key, String label, List<UUID> documentIds,
                                List<String> values) {

    public SystemModelSignal {
        documentIds = List.copyOf(documentIds);
        values = List.copyOf(values);
    }

    public enum Kind {
        /** A code mentioned in shared documents that none of them defines. */
        UNDEFINED_CODE,
        /** One abbreviation expanded differently in two or more documents: a structural contradiction. */
        TERM_CONFLICT,
        /** A process with numbered steps and no responsible role. */
        PROCESS_WITHOUT_OWNER
    }
}
