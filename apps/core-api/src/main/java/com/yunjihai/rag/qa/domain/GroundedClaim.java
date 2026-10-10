package com.yunjihai.rag.qa.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record GroundedClaim(UUID claimId, String text, List<EvidenceSupport> supports) {
    public GroundedClaim {
        Objects.requireNonNull(claimId, "claimId must not be null");
        Objects.requireNonNull(text, "claim text must not be null");
        if (text.isBlank() || text.codePointCount(0, text.length()) > 2_000) {
            throw new IllegalArgumentException("claim text length is invalid");
        }
        supports = List.copyOf(Objects.requireNonNull(supports, "supports must not be null"));
        if (supports.isEmpty() || supports.size() > 15 || supports.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("claim supports are invalid");
        }
        var ids = new HashSet<UUID>();
        if (supports.stream().anyMatch(support -> !ids.add(support.evidenceId()))) {
            throw new IllegalArgumentException("claim supports must reference unique evidence");
        }
    }
}
