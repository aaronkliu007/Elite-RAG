package com.yunjihai.rag.solution.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class Solution {

    private final UUID id;
    private final UUID spaceId;
    private final UUID createdBy;
    private final Instant createdAt;
    private final long solutionRevision;
    private final long revision;
    private final UUID currentRequirementVersionId;
    private final Optional<UUID> currentOutlineVersionId;
    private final Optional<VersionConfirmation> requirementConfirmation;
    private final Optional<VersionConfirmation> outlineConfirmation;
    private final Map<UUID, UUID> adoptedSectionVersionIds;
    private final SolutionPhase phase;
    private final Optional<UUID> finalizedVersionId;

    private Solution(
            UUID id,
            UUID spaceId,
            UUID createdBy,
            Instant createdAt,
            long solutionRevision,
            long revision,
            UUID currentRequirementVersionId,
            Optional<UUID> currentOutlineVersionId,
            Optional<VersionConfirmation> requirementConfirmation,
            Optional<VersionConfirmation> outlineConfirmation,
            Map<UUID, UUID> adoptedSectionVersionIds,
            SolutionPhase phase,
            Optional<UUID> finalizedVersionId) {
        this.id = Objects.requireNonNull(id, "id");
        this.spaceId = Objects.requireNonNull(spaceId, "spaceId");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (solutionRevision < 1 || revision < 0) {
            throw new IllegalArgumentException("invalid solution revision");
        }
        this.solutionRevision = solutionRevision;
        this.revision = revision;
        this.currentRequirementVersionId = Objects.requireNonNull(
                currentRequirementVersionId, "currentRequirementVersionId");
        this.currentOutlineVersionId = Objects.requireNonNull(currentOutlineVersionId, "currentOutlineVersionId");
        this.requirementConfirmation = Objects.requireNonNull(
                requirementConfirmation, "requirementConfirmation");
        this.outlineConfirmation = Objects.requireNonNull(outlineConfirmation, "outlineConfirmation");
        this.adoptedSectionVersionIds = Map.copyOf(
                Objects.requireNonNull(adoptedSectionVersionIds, "adoptedSectionVersionIds"));
        this.phase = Objects.requireNonNull(phase, "phase");
        this.finalizedVersionId = Objects.requireNonNull(finalizedVersionId, "finalizedVersionId");
    }

    public static Solution create(
            UUID solutionId,
            UUID spaceId,
            UUID actorId,
            RequirementVersion initial,
            Instant at) {
        Objects.requireNonNull(initial, "initial");
        return new Solution(
                solutionId,
                spaceId,
                actorId,
                at,
                1,
                0,
                initial.id(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                SolutionPhase.REQUIREMENTS_DRAFT,
                Optional.empty());
    }

    public Solution reviseRequirements(RequirementVersion next, long expectedRevision) {
        requireRevision(expectedRevision);
        requireNotFinalized();
        Objects.requireNonNull(next, "next");
        return copy(
                solutionRevision,
                next.id(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                SolutionPhase.REQUIREMENTS_DRAFT,
                Optional.empty());
    }

    public Solution confirmRequirements(UUID actorId, Instant at, long expectedRevision) {
        requireRevision(expectedRevision);
        requireNotFinalized();
        if (phase != SolutionPhase.REQUIREMENTS_DRAFT) {
            throw new SolutionLifecycleException("REQUIREMENTS_NOT_DRAFT");
        }
        return copy(
                solutionRevision,
                currentRequirementVersionId,
                currentOutlineVersionId,
                Optional.of(new VersionConfirmation(currentRequirementVersionId, actorId, at)),
                Optional.empty(),
                Map.of(),
                SolutionPhase.REQUIREMENTS_CONFIRMED,
                Optional.empty());
    }

    public Solution reviseOutline(OutlineVersion next, long expectedRevision) {
        requireRevision(expectedRevision);
        requireNotFinalized();
        Objects.requireNonNull(next, "next");
        if (requirementConfirmation.isEmpty()
                || !requirementConfirmation.orElseThrow().versionId().equals(currentRequirementVersionId)) {
            throw new SolutionLifecycleException("REQUIREMENTS_NOT_CONFIRMED");
        }
        return copy(
                solutionRevision,
                currentRequirementVersionId,
                Optional.of(next.id()),
                requirementConfirmation,
                Optional.empty(),
                Map.of(),
                SolutionPhase.OUTLINE_DRAFT,
                Optional.empty());
    }

    public Solution confirmOutline(UUID actorId, Instant at, long expectedRevision) {
        requireRevision(expectedRevision);
        requireNotFinalized();
        if (phase != SolutionPhase.OUTLINE_DRAFT || currentOutlineVersionId.isEmpty()) {
            throw new SolutionLifecycleException("OUTLINE_NOT_DRAFT");
        }
        return copy(
                solutionRevision,
                currentRequirementVersionId,
                currentOutlineVersionId,
                requirementConfirmation,
                Optional.of(new VersionConfirmation(currentOutlineVersionId.orElseThrow(), actorId, at)),
                Map.of(),
                SolutionPhase.OUTLINE_CONFIRMED,
                Optional.empty());
    }

    public Solution adoptSection(
            UUID sectionId,
            UUID sectionVersionId,
            long expectedRevision) {
        requireRevision(expectedRevision);
        requireNotFinalized();
        Objects.requireNonNull(sectionId, "sectionId");
        Objects.requireNonNull(sectionVersionId, "sectionVersionId");
        if (outlineConfirmation.isEmpty()
                || currentOutlineVersionId.isEmpty()
                || !outlineConfirmation.orElseThrow().versionId().equals(currentOutlineVersionId.orElseThrow())) {
            throw new SolutionLifecycleException("OUTLINE_NOT_CONFIRMED");
        }
        var adopted = new HashMap<>(adoptedSectionVersionIds);
        adopted.put(sectionId, sectionVersionId);
        return copy(
                solutionRevision,
                currentRequirementVersionId,
                currentOutlineVersionId,
                requirementConfirmation,
                outlineConfirmation,
                adopted,
                SolutionPhase.IN_REVIEW,
                Optional.empty());
    }

    public Solution markFinalized(UUID finalVersionId, long expectedRevision) {
        requireRevision(expectedRevision);
        requireNotFinalized();
        Objects.requireNonNull(finalVersionId, "finalVersionId");
        if (adoptedSectionVersionIds.isEmpty()) {
            throw new SolutionLifecycleException("NO_ADOPTED_SECTION");
        }
        return copy(
                solutionRevision,
                currentRequirementVersionId,
                currentOutlineVersionId,
                requirementConfirmation,
                outlineConfirmation,
                adoptedSectionVersionIds,
                SolutionPhase.FINALIZED,
                Optional.of(finalVersionId));
    }

    public Solution startNewRevision(RequirementVersion next, long expectedRevision) {
        requireRevision(expectedRevision);
        Objects.requireNonNull(next, "next");
        if (phase != SolutionPhase.FINALIZED) {
            throw new SolutionLifecycleException("SOLUTION_NOT_FINALIZED");
        }
        return copy(
                solutionRevision + 1,
                next.id(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                SolutionPhase.REQUIREMENTS_DRAFT,
                Optional.empty());
    }

    private Solution copy(
            long nextSolutionRevision,
            UUID nextRequirementVersionId,
            Optional<UUID> nextOutlineVersionId,
            Optional<VersionConfirmation> nextRequirementConfirmation,
            Optional<VersionConfirmation> nextOutlineConfirmation,
            Map<UUID, UUID> nextAdoptedSections,
            SolutionPhase nextPhase,
            Optional<UUID> nextFinalizedVersionId) {
        return new Solution(
                id,
                spaceId,
                createdBy,
                createdAt,
                nextSolutionRevision,
                revision + 1,
                nextRequirementVersionId,
                nextOutlineVersionId,
                nextRequirementConfirmation,
                nextOutlineConfirmation,
                nextAdoptedSections,
                nextPhase,
                nextFinalizedVersionId);
    }

    private void requireRevision(long expectedRevision) {
        if (revision != expectedRevision) {
            throw new ConcurrentSolutionUpdateException();
        }
    }

    private void requireNotFinalized() {
        if (phase == SolutionPhase.FINALIZED) {
            throw new SolutionLifecycleException("SOLUTION_FINALIZED");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID spaceId() {
        return spaceId;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long solutionRevision() {
        return solutionRevision;
    }

    public long revision() {
        return revision;
    }

    public UUID currentRequirementVersionId() {
        return currentRequirementVersionId;
    }

    public Optional<UUID> currentOutlineVersionId() {
        return currentOutlineVersionId;
    }

    public Optional<VersionConfirmation> requirementConfirmation() {
        return requirementConfirmation;
    }

    public Optional<VersionConfirmation> outlineConfirmation() {
        return outlineConfirmation;
    }

    public Map<UUID, UUID> adoptedSectionVersionIds() {
        return adoptedSectionVersionIds;
    }

    public SolutionPhase phase() {
        return phase;
    }

    public Optional<UUID> finalizedVersionId() {
        return finalizedVersionId;
    }
}
