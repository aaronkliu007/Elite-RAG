package com.yunjihai.rag.solution.proposal;

import java.util.Objects;
import java.util.UUID;

public record ProposalRegistrationRequest(ProposalKey key, UUID requirementVersionId,
        long expectedRevision, String inputFingerprint) {
    public ProposalRegistrationRequest {
        Objects.requireNonNull(key);
        Objects.requireNonNull(requirementVersionId);
        if (expectedRevision < 0 || inputFingerprint == null
                || !inputFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid proposal input");
        }
    }
}
