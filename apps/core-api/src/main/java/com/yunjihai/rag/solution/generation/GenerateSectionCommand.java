package com.yunjihai.rag.solution.generation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record GenerateSectionCommand(
        UUID actorId,
        UUID spaceId,
        UUID solutionId,
        UUID sectionId,
        UUID attemptId,
        UUID sectionVersionId,
        UUID retrievalRequestId,
        String clientKey,
        Optional<UUID> baselineSectionVersionId,
        long expectedRevision,
        Instant at) {

    public GenerateSectionCommand {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(solutionId, "solutionId");
        Objects.requireNonNull(sectionId, "sectionId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(sectionVersionId, "sectionVersionId");
        Objects.requireNonNull(retrievalRequestId, "retrievalRequestId");
        Objects.requireNonNull(clientKey, "clientKey");
        clientKey = clientKey.strip();
        if (clientKey.isEmpty() || clientKey.length() > 128) {
            throw new IllegalArgumentException("invalid clientKey");
        }
        baselineSectionVersionId = Objects.requireNonNull(
                baselineSectionVersionId, "baselineSectionVersionId");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
        Objects.requireNonNull(at, "at");
    }
}
