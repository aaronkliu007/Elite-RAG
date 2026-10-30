package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.solution.proposal.ProposalAttempt;
import com.yunjihai.rag.solution.proposal.ProposalKind;

public interface SolutionProposalPort {
    ProposalAttempt generateRequirements(SolutionProposalService.Command command);
    ProposalAttempt generateOutline(SolutionProposalService.Command command);
    ProposalAttempt find(SolutionProposalService.Command command, ProposalKind kind);
}
