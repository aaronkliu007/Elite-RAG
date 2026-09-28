package com.yunjihai.rag.solution.domain;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record Claim(
        UUID claimId,
        String text,
        boolean factual,
        Set<UUID> evidenceIds,
        Set<RiskType> riskTypes) {

    public Claim {
        Objects.requireNonNull(claimId, "claimId");
        text = SolutionText.required(text, "text");
        evidenceIds = Set.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
        riskTypes = Set.copyOf(Objects.requireNonNull(riskTypes, "riskTypes"));
    }

    @Override
    public String toString() {
        return "Claim[claimId=" + claimId
                + ", factual=" + factual
                + ", evidenceCount=" + evidenceIds.size()
                + ", riskTypes=" + riskTypes + "]";
    }
}
