package com.yunjihai.rag.solution.application;

import java.util.UUID;

public interface SolutionSectionAdoptionPort {
    SolutionCommandResult adopt(UUID actorId, UUID solutionId, UUID sectionId, UUID sectionVersionId,
            long expectedRevision, String clientKey);
}
