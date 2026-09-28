package com.yunjihai.rag.retrieval.domain;

import java.time.Duration;
import java.util.Objects;

public record RetrievalPolicy(
        int lexicalLimit,
        int vectorLimit,
        int fusedLimit,
        int rrfK,
        int maxPerDocument,
        double nearDuplicateThreshold,
        Duration recallTimeout,
        Duration rerankTimeout,
        int minEvidenceCount,
        int maxEvidenceCount) {

    public RetrievalPolicy {
        if (lexicalLimit <= 0 || vectorLimit <= 0 || fusedLimit <= 0) {
            throw new IllegalArgumentException("retrieval limits must be positive");
        }
        if (fusedLimit > lexicalLimit + vectorLimit) {
            throw new IllegalArgumentException("fused limit exceeds total recall capacity");
        }
        if (rrfK <= 0 || maxPerDocument <= 0) {
            throw new IllegalArgumentException("ranking limits must be positive");
        }
        if (!Double.isFinite(nearDuplicateThreshold)
                || nearDuplicateThreshold <= 0
                || nearDuplicateThreshold > 1) {
            throw new IllegalArgumentException("near duplicate threshold is invalid");
        }
        requirePositive(recallTimeout, "recallTimeout");
        requirePositive(rerankTimeout, "rerankTimeout");
        if (minEvidenceCount < 8
                || maxEvidenceCount > 15
                || minEvidenceCount > maxEvidenceCount) {
            throw new IllegalArgumentException("evidence count range is invalid");
        }
    }

    public static RetrievalPolicy defaults() {
        return new RetrievalPolicy(
                50,
                50,
                30,
                60,
                3,
                0.85,
                Duration.ofMillis(1200),
                Duration.ofMillis(800),
                8,
                15);
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
