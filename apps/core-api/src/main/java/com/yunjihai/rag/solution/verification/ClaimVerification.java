package com.yunjihai.rag.solution.verification;

import java.util.Objects;
import java.util.UUID;

public record ClaimVerification(UUID claimId, CitationVerdict verdict, String reasonCode) {

    public ClaimVerification {
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (!reasonCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid verification reason code");
        }
    }
}
