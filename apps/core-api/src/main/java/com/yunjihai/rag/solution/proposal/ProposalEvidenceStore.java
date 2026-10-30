package com.yunjihai.rag.solution.proposal;

import java.util.Optional;
import java.util.UUID;

public interface ProposalEvidenceStore {
    void save(UUID attemptId, ProposalEvidenceSnapshot snapshot);
    Optional<ProposalEvidenceSnapshot> find(UUID attemptId);
}
