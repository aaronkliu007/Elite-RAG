package com.yunjihai.rag.retrieval.selection;

import java.util.Objects;
import java.util.UUID;

public record RerankScore(UUID candidateId, double score) {

    public RerankScore {
        Objects.requireNonNull(candidateId, "candidateId must not be null");
    }
}
