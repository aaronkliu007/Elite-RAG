package com.yunjihai.rag.retrieval.domain;

public record RetrievalStats(
        int lexicalCount,
        int vectorCount,
        int fusedCount,
        int reauthorizedCount,
        int selectedCount) {

    public RetrievalStats {
        if (lexicalCount < 0
                || vectorCount < 0
                || fusedCount < 0
                || reauthorizedCount < 0
                || selectedCount < 0) {
            throw new IllegalArgumentException("retrieval counts must not be negative");
        }
    }
}
