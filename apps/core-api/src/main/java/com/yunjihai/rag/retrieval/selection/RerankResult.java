package com.yunjihai.rag.retrieval.selection;

import com.yunjihai.rag.retrieval.domain.DegradationReason;
import com.yunjihai.rag.retrieval.domain.ScoredCandidate;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record RerankResult(
        List<ScoredCandidate> candidates,
        Set<DegradationReason> degradationReasons) {

    public RerankResult {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
        Objects.requireNonNull(degradationReasons, "degradationReasons must not be null");
        degradationReasons = degradationReasons.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(degradationReasons));
    }

    public boolean degraded() {
        return !degradationReasons.isEmpty();
    }
}
