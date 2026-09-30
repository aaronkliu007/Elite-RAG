package com.yunjihai.rag.retrieval.embedding;

import java.util.List;
import java.util.Objects;

public record EmbeddingVector(List<Double> values) {
    public EmbeddingVector {
        values = List.copyOf(Objects.requireNonNull(values));
        if (values.isEmpty() || values.stream().anyMatch(
                value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("embedding vector must contain finite values");
        }
    }

    public int dimension() {
        return values.size();
    }
}
