package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.solution.proposal.ProposalAttempt;

public interface DocumentRequirementProposalPort {
    ProposalAttempt generate(DocumentRequirementProposalService.Command command);
    ProposalAttempt find(DocumentRequirementProposalService.Command command);
}
