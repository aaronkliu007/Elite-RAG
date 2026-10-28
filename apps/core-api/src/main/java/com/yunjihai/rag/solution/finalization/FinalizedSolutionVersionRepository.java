package com.yunjihai.rag.solution.finalization;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface FinalizedSolutionVersionRepository {
    Optional<FinalizedSolutionVersion> findById(UUID versionId);
}
