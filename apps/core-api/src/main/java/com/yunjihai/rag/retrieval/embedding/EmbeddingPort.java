package com.yunjihai.rag.retrieval.embedding;

import java.util.List;
import java.util.Objects;

@FunctionalInterface
public interface EmbeddingPort {
    List<EmbeddingVector> embedAll(List<String> texts);

    default EmbeddingVector embed(String text) {
        Objects.requireNonNull(text, "text must not be null");
        var result = List.copyOf(Objects.requireNonNull(embedAll(List.of(text))));
        if (result.size() != 1 || result.getFirst() == null) {
            throw new IllegalStateException("embedding batch result size is invalid");
        }
        return result.getFirst();
    }
}
