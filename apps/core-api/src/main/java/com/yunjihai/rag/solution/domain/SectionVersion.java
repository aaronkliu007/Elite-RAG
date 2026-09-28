package com.yunjihai.rag.solution.domain;

import com.yunjihai.rag.solution.verification.SolutionRiskReport;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record SectionVersion(
        UUID id,
        UUID solutionId,
        UUID sectionId,
        SectionVersionKind kind,
        String content,
        List<Claim> claims,
        Set<UUID> evidenceIds,
        UUID requirementVersionId,
        UUID outlineVersionId,
        Optional<UUID> baselineSectionVersionId,
        Optional<UUID> parentVersionId,
        Optional<UUID> createdBy,
        Optional<String> modelId,
        Instant createdAt,
        SolutionRiskReport verification) {

    public SectionVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(solutionId, "solutionId");
        Objects.requireNonNull(sectionId, "sectionId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(content, "content");
        content = content.strip();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("section content must not be blank");
        }
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
        evidenceIds = Set.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
        Objects.requireNonNull(requirementVersionId, "requirementVersionId");
        Objects.requireNonNull(outlineVersionId, "outlineVersionId");
        baselineSectionVersionId = Objects.requireNonNull(
                baselineSectionVersionId, "baselineSectionVersionId");
        parentVersionId = Objects.requireNonNull(parentVersionId, "parentVersionId");
        createdBy = Objects.requireNonNull(createdBy, "createdBy");
        modelId = Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(verification, "verification");
        validateProducer(kind, createdBy, modelId);
        validateClaims(claims, evidenceIds, verification);
    }

    private static void validateProducer(
            SectionVersionKind kind,
            Optional<UUID> createdBy,
            Optional<String> modelId) {
        if (kind == SectionVersionKind.MODEL_DRAFT) {
            if (createdBy.isPresent() || modelId.isEmpty() || modelId.orElseThrow().isBlank()) {
                throw new IllegalArgumentException("model draft requires only a model producer");
            }
        } else if (createdBy.isEmpty() || modelId.isPresent()) {
            throw new IllegalArgumentException("human section version requires only an actor producer");
        }
    }

    private static void validateClaims(
            List<Claim> claims,
            Set<UUID> evidenceIds,
            SolutionRiskReport verification) {
        var claimIds = new HashSet<UUID>();
        var citedEvidence = new HashSet<UUID>();
        for (var claim : claims) {
            if (!claimIds.add(claim.claimId())) {
                throw new IllegalArgumentException("duplicate claim id");
            }
            citedEvidence.addAll(claim.evidenceIds());
        }
        if (!citedEvidence.equals(evidenceIds)) {
            throw new IllegalArgumentException("section evidence does not match claims");
        }
        var verifiedClaimIds = new HashSet<UUID>();
        verification.verifications().forEach(result -> verifiedClaimIds.add(result.claimId()));
        if (!verifiedClaimIds.equals(claimIds)
                || verifiedClaimIds.size() != verification.verifications().size()) {
            throw new IllegalArgumentException("verification does not match claims");
        }
    }

    @Override
    public String toString() {
        return "SectionVersion[id=" + id
                + ", solutionId=" + solutionId
                + ", sectionId=" + sectionId
                + ", kind=" + kind
                + ", claimCount=" + claims.size()
                + ", evidenceCount=" + evidenceIds.size()
                + ", createdAt=" + createdAt + "]";
    }
}
