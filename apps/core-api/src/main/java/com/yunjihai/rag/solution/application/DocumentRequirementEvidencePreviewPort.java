package com.yunjihai.rag.solution.application;

import java.util.UUID;

public interface DocumentRequirementEvidencePreviewPort {
    DocumentRequirementEvidencePreviewService.Preview preview(DocumentRequirementProposalService.Command command, UUID evidenceId);
}
