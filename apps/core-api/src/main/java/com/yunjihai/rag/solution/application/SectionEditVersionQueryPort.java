package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.solution.domain.SectionVersion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read model for a chapter's immutable version chain. */
public interface SectionEditVersionQueryPort {
    Optional<SectionVersion> latestEdit(UUID solutionId, UUID sectionId, UUID baselineId);
    List<UUID> listIds(UUID solutionId, UUID sectionId, int limit);
}
