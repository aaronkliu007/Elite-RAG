package com.yunjihai.rag.retrieval.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record EvidencePack(
        UUID requestId,
        List<Evidence> evidence,
        boolean degraded,
        Set<DegradationReason> degradationReasons,
        RetrievalStats stats,
        Instant completedAt) {

    public EvidencePack {
        Objects.requireNonNull(requestId, "requestId must not be null");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
        if (evidence.size() > 15) {
            throw new IllegalArgumentException("evidence pack exceeds the maximum size");
        }
        Objects.requireNonNull(degradationReasons, "degradationReasons must not be null");
        degradationReasons = degradationReasons.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(degradationReasons));
        if (degraded != !degradationReasons.isEmpty()) {
            throw new IllegalArgumentException("degraded status does not match its reasons");
        }
        Objects.requireNonNull(stats, "stats must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
    }
}
