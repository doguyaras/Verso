package com.verso.document.api;

import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SystemEntityDetail;
import com.verso.document.api.dto.SystemEntitySummary;
import com.verso.document.api.dto.SystemModelSignal;
import java.util.List;
import java.util.UUID;

/**
 * In-process contract of the light system model (ADR-0048 phase 4, ADR-0054): entities (systems, screens, codes,
 * roles, processes, steps, terms) extracted deterministically from each document at ingestion and bound to their
 * chunks. Every method takes the reader and filters on its principals inside the query (ADR-0028): an entity known only
 * from documents the reader may not read does not exist for that reader. No model call in any method.
 */
public interface DocumentSystemModel {

    /** At most this many entities per lookup. */
    int MAX_LOOKUP = 10;
    /** A lookup query longer than this is refused (it is a term or a code, not a question). */
    int MAX_QUERY_LENGTH = 200;

    /**
     * The CODE, TERM and SYSTEM entities named by {@code query} (a code, an abbreviation or a system name; folded, so
     * "İK-3" finds "IK-3"), each with its definitions and sources; at most {@code limit} ({@value #MAX_LOOKUP}).
     * Empty for a blank or too long query.
     *
     * @param reader the validated token's sub and groups (never taken from a request)
     * @param query  the caller's text; not logged
     */
    List<SystemEntityDetail> lookup(Reader reader, String query, int limit);

    /**
     * The entities of one document the reader may read (empty otherwise): what an optional suggestion pass may relate
     * (ADR-0054: suggestions only between entities the deterministic pass found).
     */
    List<SystemEntitySummary> documentEntities(Reader reader, UUID documentId);

    /**
     * The chunks of one READY document the reader may read, in order, with text and hash; at most {@code limit}.
     * For the optional local-model suggestion pass, which must not store or log the text.
     */
    List<SourceChunk> documentChunks(Reader reader, UUID documentId, int limit);

    /**
     * The knowledge-health signals (ADR-0052) over the shared documents the admin may read: undefined codes,
     * conflicting term definitions, processes without an owner; at most {@code limit} per kind.
     */
    List<SystemModelSignal> signals(Reader admin, int limit);
}
