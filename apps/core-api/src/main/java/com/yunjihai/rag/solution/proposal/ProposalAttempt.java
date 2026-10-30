package com.yunjihai.rag.solution.proposal;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ProposalAttempt(UUID id, ProposalRegistrationRequest request,
        ProposalStatus status, Optional<String> resultJson, Optional<String> failureCode,
        Instant createdAt, Instant updatedAt, Instant leaseUntil) {
    public ProposalAttempt {
        Objects.requireNonNull(id);
        Objects.requireNonNull(request);
        Objects.requireNonNull(status);
        resultJson = Objects.requireNonNull(resultJson);
        failureCode = Objects.requireNonNull(failureCode);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
        Objects.requireNonNull(leaseUntil);
        if (updatedAt.isBefore(createdAt)
                || (status == ProposalStatus.SUCCEEDED) != resultJson.isPresent()
                || ((status == ProposalStatus.FAILED || status == ProposalStatus.INDETERMINATE)
                    != failureCode.isPresent())) {
            throw new IllegalArgumentException("invalid proposal attempt state");
        }
    }

    public ProposalKey key() { return request.key(); }

    @Override public String toString() {
        return "ProposalAttempt[id=" + id + ", status=" + status
                + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt + "]";
    }
}
