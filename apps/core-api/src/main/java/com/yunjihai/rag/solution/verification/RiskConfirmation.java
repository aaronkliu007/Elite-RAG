package com.yunjihai.rag.solution.verification;

import com.yunjihai.rag.solution.domain.Claim;
import com.yunjihai.rag.solution.domain.SectionVersion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record RiskConfirmation(
        UUID id,
        UUID solutionId,
        UUID sectionVersionId,
        UUID claimId,
        Set<UUID> evidenceIds,
        UUID requirementVersionId,
        UUID outlineVersionId,
        String claimFingerprint,
        UUID confirmedBy,
        String reason,
        Instant confirmedAt) {

    public RiskConfirmation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(solutionId, "solutionId");
        Objects.requireNonNull(sectionVersionId, "sectionVersionId");
        Objects.requireNonNull(claimId, "claimId");
        evidenceIds = Set.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
        Objects.requireNonNull(requirementVersionId, "requirementVersionId");
        Objects.requireNonNull(outlineVersionId, "outlineVersionId");
        Objects.requireNonNull(claimFingerprint, "claimFingerprint");
        if (!claimFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid claim fingerprint");
        }
        Objects.requireNonNull(confirmedBy, "confirmedBy");
        Objects.requireNonNull(reason, "reason");
        reason = reason.strip();
        if (reason.isEmpty() || reason.length() > 500) {
            throw new IllegalArgumentException("risk confirmation reason is invalid");
        }
        Objects.requireNonNull(confirmedAt, "confirmedAt");
    }

    public static RiskConfirmation create(
            UUID id,
            UUID solutionId,
            SectionVersion section,
            Claim claim,
            UUID actorId,
            String reason,
            Instant at) {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(claim, "claim");
        return new RiskConfirmation(
                id, solutionId, section.id(), claim.claimId(), claim.evidenceIds(),
                section.requirementVersionId(), section.outlineVersionId(), fingerprint(claim),
                actorId, reason, at);
    }

    public boolean matches(SectionVersion section) {
        if (section == null
                || !solutionId.equals(section.solutionId())
                || !sectionVersionId.equals(section.id())
                || !requirementVersionId.equals(section.requirementVersionId())
                || !outlineVersionId.equals(section.outlineVersionId())) {
            return false;
        }
        return section.claims().stream()
                .filter(claim -> claim.claimId().equals(claimId))
                .anyMatch(claim -> evidenceIds.equals(claim.evidenceIds())
                        && claimFingerprint.equals(fingerprint(claim)));
    }

    public String bindingKey() {
        return sectionVersionId + ":" + claimId + ":" + claimFingerprint;
    }

    private static String fingerprint(Claim claim) {
        var evidence = claim.evidenceIds().stream().map(UUID::toString).sorted()
                .collect(Collectors.joining(","));
        var risks = claim.riskTypes().stream().map(Enum::name).sorted()
                .collect(Collectors.joining(","));
        var canonical = claim.claimId() + "\n" + claim.text() + "\n" + claim.factual()
                + "\n" + evidence + "\n" + risks;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    @Override
    public String toString() {
        return "RiskConfirmation[id=" + id + ", solutionId=" + solutionId
                + ", sectionVersionId=" + sectionVersionId + ", claimId=" + claimId
                + ", evidenceCount=" + evidenceIds.size() + ", confirmedBy=" + confirmedBy
                + ", confirmedAt=" + confirmedAt + "]";
    }
}
