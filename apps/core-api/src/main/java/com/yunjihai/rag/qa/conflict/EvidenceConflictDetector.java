package com.yunjihai.rag.qa.conflict;

import com.yunjihai.rag.retrieval.domain.Evidence;

import java.util.List;

@FunctionalInterface
public interface EvidenceConflictDetector {
    enum ConflictKind { NONE, IDENTITY, APPROVAL, STRUCTURED_TABLE, TYPED_FACT, OTHER }

    boolean hasConflict(List<Evidence> evidence);

    /** Optional, bounded diagnostic category; custom detectors remain source compatible. */
    default ConflictKind conflictKind(List<Evidence> evidence) {
        return hasConflict(evidence) ? ConflictKind.OTHER : ConflictKind.NONE;
    }
}
