package com.yunjihai.rag.solution.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record SolutionSnapshot(
        UUID id,
        UUID spaceId,
        UUID createdBy,
        Instant createdAt,
        SolutionName name,
        long solutionRevision,
        long revision,
        UUID currentRequirementVersionId,
        Optional<UUID> currentOutlineVersionId,
        Optional<VersionConfirmation> requirementConfirmation,
        Optional<VersionConfirmation> outlineConfirmation,
        Map<UUID, UUID> adoptedSectionVersionIds,
        SolutionPhase phase,
        Optional<UUID> finalizedVersionId) {

    public SolutionSnapshot {
        Objects.requireNonNull(id);
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(createdBy);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(name);
        Objects.requireNonNull(currentRequirementVersionId);
        Objects.requireNonNull(currentOutlineVersionId);
        Objects.requireNonNull(requirementConfirmation);
        Objects.requireNonNull(outlineConfirmation);
        adoptedSectionVersionIds = Map.copyOf(adoptedSectionVersionIds);
        Objects.requireNonNull(phase);
        Objects.requireNonNull(finalizedVersionId);
    }
}
