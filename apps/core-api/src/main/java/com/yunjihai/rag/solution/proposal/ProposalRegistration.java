package com.yunjihai.rag.solution.proposal;

import java.util.Objects;

public record ProposalRegistration(ProposalAttempt attempt, boolean created) {
    public ProposalRegistration { Objects.requireNonNull(attempt); }
}
