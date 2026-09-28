package com.yunjihai.rag.solution.finalization;

import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.solution.domain.Solution;

@FunctionalInterface
public interface SolutionFinalizationStore {

    void commit(
            Solution updated,
            long expectedRevision,
            FinalizedSolutionVersion finalized,
            AuditCommand successAudit);
}
