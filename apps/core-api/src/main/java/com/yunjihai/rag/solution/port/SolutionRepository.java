package com.yunjihai.rag.solution.port;

import com.yunjihai.rag.solution.domain.Solution;

import java.util.Optional;
import java.util.UUID;

public interface SolutionRepository {

    Optional<Solution> findById(UUID solutionId);

    default void lockById(UUID solutionId) {
        if (findById(solutionId).isEmpty()) throw new com.yunjihai.rag.access.AccessDeniedAsNotFoundException();
    }

    void insert(Solution solution);

    void replace(Solution solution, long expectedRevision);
}
