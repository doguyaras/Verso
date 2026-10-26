package com.verso.document.systemmodel;

import com.verso.document.api.enums.SystemEntityType;
import com.verso.document.api.enums.SystemRelationType;
import java.util.List;

/**
 * The system model of one document as the deterministic pass extracted it (ADR-0054): entities, relations and
 * mentions, each bound to the position (chunk index) of the chunk it was read from; the repository maps positions to
 * chunk ids and hashes. Document text: {@link #toString()} prints counts only.
 *
 * @param truncated true when the per-document entity bound cut the extraction
 */
public record ExtractedModel(List<Entity> entities, List<Relation> relations, List<Mention> mentions,
                             boolean truncated) {

    public ExtractedModel {
        entities = List.copyOf(entities);
        relations = List.copyOf(relations);
        mentions = List.copyOf(mentions);
    }

    /** An entity's identity inside one document: its type and folded key. */
    public record Ref(SystemEntityType type, String key) {
    }

    /** @param ordinal the step number of a STEP, else null */
    public record Entity(Ref ref, String label, Integer ordinal) {
    }

    /** MENTIONED_IN: the entity occurs in the chunk at {@code chunkIndex}; {@code defining} when it is defined there. */
    public record Mention(Ref ref, int chunkIndex, boolean defining) {
    }

    /** A relation; DEFINES has no target but a value (the expansion or description), every other type a target. */
    public record Relation(SystemRelationType type, Ref source, Ref target, String value, int chunkIndex) {
    }

    @Override
    public String toString() {
        return "ExtractedModel[entities=" + entities.size() + ", relations=" + relations.size() + ", mentions="
                + mentions.size() + ", truncated=" + truncated + "]";
    }
}
