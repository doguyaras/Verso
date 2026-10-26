package com.verso.document.api.dto;

import java.util.Arrays;
import java.util.Objects;

/**
 * A question embedded with the documents' own local model (ADR-0049): computed once per question and handed back to
 * {@link com.verso.document.api.DocumentRetrieval#search(Reader, String, QuestionEmbedding, int)}, so the qa module can
 * look up its verified answers with the same vector without a second embedding call on the hot path (ADR-0008). The
 * vector is derived from the question text and is never logged; {@link #toString()} prints the model only.
 *
 * @param model  the embedding model that produced the vector (verso.document.embedding-model)
 * @param vector the unit vector, as many dimensions as the documents' vectors
 */
public record QuestionEmbedding(String model, float[] vector) {

    public QuestionEmbedding {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(vector, "vector");
        vector = vector.clone();
    }

    @Override
    public float[] vector() {
        return vector.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof QuestionEmbedding that && model.equals(that.model) && Arrays.equals(vector, that.vector);
    }

    @Override
    public int hashCode() {
        return 31 * model.hashCode() + Arrays.hashCode(vector);
    }

    @Override
    public String toString() {
        return "QuestionEmbedding[model=" + model + ", dimensions=" + vector.length + "]";
    }
}
