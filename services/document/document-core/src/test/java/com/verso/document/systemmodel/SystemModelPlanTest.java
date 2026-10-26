package com.verso.document.systemmodel;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.systemmodel.SystemModelPlan.Stored;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Incremental updates (ADR-0054): only a changed document, or a new extractor version, is extracted again. */
class SystemModelPlanTest {

    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);

    @Test
    void needsExtraction_whenNeverExtracted_isTrue() {
        assertThat(SystemModelPlan.needsExtraction(null, 1, SystemModelPlan.digest(List.of(A)))).isTrue();
    }

    @Test
    void needsExtraction_whenChunksAndVersionUnchanged_isFalse() {
        String digest = SystemModelPlan.digest(List.of(A, B));

        assertThat(SystemModelPlan.needsExtraction(new Stored(1, digest), 1, digest)).isFalse();
    }

    @Test
    void needsExtraction_whenOneChunkHashChanges_isTrue() {
        Stored stored = new Stored(1, SystemModelPlan.digest(List.of(A, B)));

        assertThat(SystemModelPlan.needsExtraction(stored, 1, SystemModelPlan.digest(List.of(A, C)))).isTrue();
        assertThat(SystemModelPlan.needsExtraction(stored, 1, SystemModelPlan.digest(List.of(B, A)))).isTrue();
        assertThat(SystemModelPlan.needsExtraction(stored, 1, SystemModelPlan.digest(List.of(A)))).isTrue();
    }

    @Test
    void needsExtraction_whenExtractorVersionChanges_isTrue() {
        String digest = SystemModelPlan.digest(List.of(A));

        assertThat(SystemModelPlan.needsExtraction(new Stored(1, digest), 2, digest)).isTrue();
    }

    @Test
    void extract_whenOneChunkChanges_changesOnlyWhatThatChunkSaid() {
        List<SystemModelExtractor.ChunkText> before = List.of(new SystemModelExtractor.ChunkText(0, "WMS-204: Stok yok"),
                new SystemModelExtractor.ChunkText(1, "FEFO (First Expired First Out)"));
        List<SystemModelExtractor.ChunkText> after = List.of(new SystemModelExtractor.ChunkText(0, "WMS-205: Raf dolu"),
                new SystemModelExtractor.ChunkText(1, "FEFO (First Expired First Out)"));

        ExtractedModel old = SystemModelExtractor.extract(before);
        ExtractedModel now = SystemModelExtractor.extract(after);

        assertThat(old.entities()).anyMatch(e -> e.ref().key().equals("WMS-204"));
        assertThat(now.entities()).noneMatch(e -> e.ref().key().equals("WMS-204"))
                .anyMatch(e -> e.ref().key().equals("WMS-205"));
        assertThat(now.relations()).filteredOn(r -> r.source().key().equals("FEFO"))
                .isEqualTo(old.relations().stream().filter(r -> r.source().key().equals("FEFO")).toList());
    }
}
