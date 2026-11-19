package com.yunjihai.rag.qa.conflict;

import com.yunjihai.rag.ingestion.CanonicalTableCell;
import com.yunjihai.rag.retrieval.domain.Evidence;
import java.util.List;

@FunctionalInterface
public interface TableEvidenceSource {
    List<CanonicalTableCell> load(Evidence evidence);

    /** Proof is optional; absence or failure must never be treated as independent context. */
    default boolean hasStandaloneBudgetContext(Evidence evidence) { return false; }

    default boolean hasStandaloneBackupContext(Evidence evidence) { return false; }
}
