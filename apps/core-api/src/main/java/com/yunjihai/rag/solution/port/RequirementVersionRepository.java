package com.yunjihai.rag.solution.port;

import com.yunjihai.rag.solution.domain.RequirementVersion;

import java.util.Optional;
import java.util.UUID;

public interface RequirementVersionRepository {

    Optional<RequirementVersion> findById(UUID requirementVersionId);

    void insert(RequirementVersion requirementVersion);

    default void insert(UUID solutionId, RequirementVersion requirementVersion) {
        insert(requirementVersion);
    }
}
