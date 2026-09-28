package com.yunjihai.rag.solution.finalization;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record FinalizedSolutionVersion(
        UUID id,
        UUID solutionId,
        long solutionRevision,
        UUID requirementVersionId,
        UUID outlineVersionId,
        List<UUID> sectionVersionIds,
        List<UUID> riskConfirmationIds,
        UUID createdBy,
        Instant createdAt,
        String contentHash) {

    public FinalizedSolutionVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(solutionId, "solutionId");
        if (solutionRevision < 1) {
            throw new IllegalArgumentException("solutionRevision must be positive");
        }
        Objects.requireNonNull(requirementVersionId, "requirementVersionId");
        Objects.requireNonNull(outlineVersionId, "outlineVersionId");
        sectionVersionIds = List.copyOf(Objects.requireNonNull(sectionVersionIds, "sectionVersionIds"));
        riskConfirmationIds = List.copyOf(Objects.requireNonNull(
                riskConfirmationIds, "riskConfirmationIds"));
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(contentHash, "contentHash");
        if (!contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid content hash");
        }
    }
}
