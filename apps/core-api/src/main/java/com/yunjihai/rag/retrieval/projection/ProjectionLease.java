package com.yunjihai.rag.retrieval.projection;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProjectionLease(
        UUID eventId,
        long sequence,
        ProjectionScope scope,
        UUID spaceId,
        UUID documentId,
        UUID versionId,
        Long documentRevision,
        UUID progressDocumentId,
        UUID leaseToken,
        int attempt,
        String workerId,
        Instant leaseUntil) {

    public ProjectionLease {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(leaseToken);
        Objects.requireNonNull(workerId);
        Objects.requireNonNull(leaseUntil);
        if (sequence <= 0 || attempt <= 0 || workerId.isBlank()) {
            throw new IllegalArgumentException("投影租约无效");
        }
    }
}
