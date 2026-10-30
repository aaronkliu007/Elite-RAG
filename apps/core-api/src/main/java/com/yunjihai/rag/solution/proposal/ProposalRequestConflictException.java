package com.yunjihai.rag.solution.proposal;

public final class ProposalRequestConflictException extends RuntimeException {
    public ProposalRequestConflictException() {
        super("SOLUTION_PROPOSAL_KEY_REUSED", null, false, true);
    }
}
