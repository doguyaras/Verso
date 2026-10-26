package com.verso.document.service.impl;

import com.verso.document.api.DocumentDiagnostics;
import com.verso.document.api.dto.ChunkPairScan;
import com.verso.document.api.dto.SharedDocument;
import com.verso.document.repository.DiagnosticsRepository;
import com.verso.document.repository.RetrievalRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link DocumentDiagnostics} (ADR-0052): short read-only transactions, no model call. The pair search runs the HNSW
 * nearest-neighbour query once per scanned chunk; the scan is bounded by the caller's {@code maxChunks} per run and
 * continues from its cursor the next night, so a large installation is covered over several runs.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentDiagnosticsServiceImpl implements DocumentDiagnostics {

    private static final int MAX_SCAN = 10_000;
    private static final int MAX_NEIGHBOURS = 10;

    private final DiagnosticsRepository repository;
    private final RetrievalRepository retrieval;
    private final TransactionTemplate readOnly;

    public DocumentDiagnosticsServiceImpl(DiagnosticsRepository repository, RetrievalRepository retrieval,
                                          TransactionTemplate transaction) {
        this.repository = repository;
        this.retrieval = retrieval;
        this.readOnly = new TransactionTemplate(transaction.getTransactionManager());
        this.readOnly.setReadOnly(true);
    }

    @Override
    public List<SharedDocument> sharedDocuments(int limit) {
        if (limit < 1 || limit > MAX_DOCUMENTS) throw new IllegalArgumentException("limit must be 1.." + MAX_DOCUMENTS);
        List<SharedDocument> documents = readOnly.execute(status -> repository.sharedDocuments(limit));
        return documents == null ? List.of() : documents;
    }

    @Override
    public Map<UUID, String> collectionNames(Collection<UUID> collectionIds) {
        Set<UUID> ids = new LinkedHashSet<>(collectionIds.stream().filter(Objects::nonNull).toList());
        if (ids.isEmpty()) return Map.of();
        if (ids.size() > MAX_DOCUMENTS) throw new IllegalArgumentException("too many collections");
        Map<UUID, String> names = readOnly.execute(status -> repository.collectionNames(ids));
        return names == null ? Map.of() : names;
    }

    @Override
    public ChunkPairScan similarChunkPairs(UUID after, int maxChunks, int neighbours, double minSimilarity) {
        if (maxChunks < 1 || maxChunks > MAX_SCAN) throw new IllegalArgumentException("maxChunks must be 1.." + MAX_SCAN);
        if (neighbours < 1 || neighbours > MAX_NEIGHBOURS) {
            throw new IllegalArgumentException("neighbours must be 1.." + MAX_NEIGHBOURS);
        }
        if (minSimilarity < 0.5 || minSimilarity > 1) throw new IllegalArgumentException("minSimilarity must be 0.5..1");
        ChunkPairScan scan = readOnly.execute(status -> {
            List<UUID> slice = repository.scanSlice(after, maxChunks);
            if (slice.isEmpty()) return new ChunkPairScan(List.of(), 0, null);
            // The neighbour query filters on document state and kind: let the HNSW scan look past filtered rows.
            retrieval.prepareFilteredScan(neighbours * 4);
            List<ChunkPairScan.Pair> pairs = repository.nearestOtherDocuments(slice, neighbours, minSimilarity).stream()
                    .map(row -> new ChunkPairScan.Pair(row.first(), row.second(), row.similarity())).toList();
            // A short slice reached the end: the next scan starts over from the first chunk.
            UUID cursor = slice.size() < maxChunks ? null : slice.getLast();
            return new ChunkPairScan(pairs, slice.size(), cursor);
        });
        return scan == null ? new ChunkPairScan(List.of(), 0, null) : scan;
    }
}
