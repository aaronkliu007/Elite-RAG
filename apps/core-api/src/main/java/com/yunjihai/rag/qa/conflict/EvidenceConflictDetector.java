package com.yunjihai.rag.qa.conflict;

import com.yunjihai.rag.retrieval.domain.Evidence;

import java.util.List;

@FunctionalInterface
public interface EvidenceConflictDetector {
    boolean hasConflict(List<Evidence> evidence);
}
