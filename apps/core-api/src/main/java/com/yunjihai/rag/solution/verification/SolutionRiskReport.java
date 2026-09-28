package com.yunjihai.rag.solution.verification;

import java.util.List;
import java.util.Objects;

public record SolutionRiskReport(List<ClaimVerification> verifications) {

    public SolutionRiskReport {
        verifications = List.copyOf(Objects.requireNonNull(verifications, "verifications"));
    }

    public boolean hasBlockingVerdict() {
        return verifications.stream().anyMatch(verification ->
                verification.verdict() == CitationVerdict.PARTIAL
                        || verification.verdict() == CitationVerdict.UNSUPPORTED);
    }
}
