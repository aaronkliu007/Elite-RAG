package com.yunjihai.rag.retrieval.search;

import com.yunjihai.rag.retrieval.domain.DegradationReason;
import com.yunjihai.rag.retrieval.domain.ScoredCandidate;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record HybridRecallResult(
        List<ScoredCandidate> candidates,
        Set<DegradationReason> degradationReasons,
        int lexicalCount,
        int vectorCount) {

    public HybridRecallResult {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
        Objects.requireNonNull(degradationReasons, "degradationReasons must not be null");
        degradationReasons = degradationReasons.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(degradationReasons));
        if (lexicalCount < 0 || vectorCount < 0) {
            throw new IllegalArgumentException("recall counts must not be negative");
        }
    }

    public boolean degraded() {
        return !degradationReasons.isEmpty();
    }
}
