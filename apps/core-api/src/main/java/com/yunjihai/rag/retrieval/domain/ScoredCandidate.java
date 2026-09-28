package com.yunjihai.rag.retrieval.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public record ScoredCandidate(
        Candidate candidate,
        double score,
        int bestSourceRank,
        Set<RecallSource> sources) {

    public ScoredCandidate {
        Objects.requireNonNull(candidate, "candidate must not be null");
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite");
        }
        if (bestSourceRank < 1) {
            throw new IllegalArgumentException("best source rank must start at one");
        }
        Objects.requireNonNull(sources, "sources must not be null");
        if (sources.isEmpty() || !sources.contains(candidate.source())) {
            throw new IllegalArgumentException("sources must contain the candidate source");
        }
        sources = Collections.unmodifiableSet(EnumSet.copyOf(sources));
    }
}
