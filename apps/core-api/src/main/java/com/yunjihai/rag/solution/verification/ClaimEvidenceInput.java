package com.yunjihai.rag.solution.verification;

import com.yunjihai.rag.retrieval.domain.Evidence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ClaimEvidenceInput(UUID claimId, String statement, List<Evidence> evidence) {

    public ClaimEvidenceInput {
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(statement, "statement");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (statement.isBlank() || evidence.isEmpty()) {
            throw new IllegalArgumentException("claim evidence input is incomplete");
        }
    }
}
