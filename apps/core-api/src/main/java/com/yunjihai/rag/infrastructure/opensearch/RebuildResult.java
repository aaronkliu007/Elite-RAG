package com.yunjihai.rag.infrastructure.opensearch;

import java.util.Objects;

public record RebuildResult(
        String generation,
        String previousIndex,
        long startSequence,
        long finalSequence,
        long indexedDocuments,
        long indexedNodes) {
    public RebuildResult {
        Objects.requireNonNull(generation);
        if (generation.isBlank() || startSequence < 0 || finalSequence < startSequence
                || indexedDocuments < 0 || indexedNodes < 0) {
            throw new IllegalArgumentException("invalid rebuild result");
        }
    }
}
