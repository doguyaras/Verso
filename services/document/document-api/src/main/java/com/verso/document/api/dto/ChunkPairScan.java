package com.verso.document.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * One slice of the contradiction scan (ADR-0052): the near-duplicate pairs found, how many chunks were scanned and the
 * last chunk reached (the next scan's cursor; null when the scan reached the end and starts over).
 */
public record ChunkPairScan(List<Pair> pairs, int scanned, UUID cursor) {

    public ChunkPairScan {
        pairs = List.copyOf(pairs);
    }

    /**
     * Two chunks of different shared documents and their cosine similarity. The chunks carry their text (for the
     * caller's in-memory comparison only, never stored or logged) and their SHA-256; {@link #toString()} prints ids.
     */
    public record Pair(SourceChunk first, SourceChunk second, double similarity) {

        @Override
        public String toString() {
            return "Pair[first=" + first + ", second=" + second + "]";
        }
    }
}
