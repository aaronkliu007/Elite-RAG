package com.yunjihai.rag.solution.generation;

import com.yunjihai.rag.solution.domain.SectionVersion;

import java.util.Optional;
import java.util.UUID;

public interface SectionVersionRepository {

    Optional<SectionVersion> findById(UUID sectionVersionId);

    SectionVersion insertIfAbsent(SectionVersion sectionVersion);

    /** Returns the draft checkpoint written for an attempt by the atomic generation commit adapter. */
    Optional<SectionVersion> findByAttemptId(UUID attemptId);
}
