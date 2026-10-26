package com.verso.document.api.dto;

import com.verso.document.api.enums.SystemEntityType;

/**
 * One entity of the system model across the documents the reader may read (ADR-0054): the same type and key in several
 * documents is one row here.
 *
 * @param key       the folded lookup key ("WMS-204", "FEFO", "depo sorumlusu")
 * @param label     the text as one document wrote it
 * @param documents readable documents that mention it
 * @param mentions  readable chunks that mention it
 */
public record SystemEntitySummary(SystemEntityType type, String key, String label, long documents, long mentions) {
}
