package com.yunjihai.rag.solution.application;

public interface DocumentRequirementProposalAdoptionPort {
    SolutionCommandResult adopt(DocumentRequirementProposalService.Command command, String adoptionKey);
}
