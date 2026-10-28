package com.yunjihai.rag.solution.generation;

import java.util.List;
import java.util.UUID;

public interface GenerationEvidenceStore {
    void saveSnapshot(UUID attemptId, List<GenerationEvidenceSource> sources);

    List<GenerationEvidenceSource> findByAttemptId(UUID attemptId);

    List<GenerationEvidenceSource> findBySectionVersionId(UUID sectionVersionId);
}
