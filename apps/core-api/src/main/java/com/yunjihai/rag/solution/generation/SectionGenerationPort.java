package com.yunjihai.rag.solution.generation;

import com.yunjihai.rag.solution.domain.Solution;

import java.time.Instant;
import java.util.UUID;

public interface SectionGenerationPort {
    SectionGenerationResult generate(GenerateSectionCommand command);

    SectionGenerationResult readAttempt(UUID actorId, UUID spaceId, UUID solutionId,
            UUID sectionId, String clientKey, Instant at);

    Solution adoptSection(UUID actorId, UUID solutionId, UUID sectionVersionId,
            long expectedRevision, Instant at);
}
