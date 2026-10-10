package com.yunjihai.rag.qa.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CitationClaim(UUID claimId, String text, List<UUID> evidenceIds) {
    public CitationClaim {
        Objects.requireNonNull(claimId, "claimId must not be null");
        Objects.requireNonNull(text, "claim text must not be null");
        if (text.isBlank() || text.codePointCount(0, text.length()) > 2_000) {
            throw new IllegalArgumentException("claim text length is invalid");
        }
        evidenceIds = List.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds must not be null"));
        if (evidenceIds.isEmpty() || evidenceIds.size() > 15
                || evidenceIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(evidenceIds).size() != evidenceIds.size()) {
            throw new IllegalArgumentException("claim evidence ids are invalid");
        }
    }

    public static CitationClaim from(GroundedClaim claim) {
        Objects.requireNonNull(claim, "claim must not be null");
        return new CitationClaim(claim.claimId(), claim.text(), claim.supports().stream()
                .map(EvidenceSupport::evidenceId).toList());
    }
}
