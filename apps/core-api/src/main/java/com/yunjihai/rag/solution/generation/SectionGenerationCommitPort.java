package com.yunjihai.rag.solution.generation;

import com.yunjihai.rag.solution.domain.SectionVersion;

import java.util.UUID;

@FunctionalInterface
public interface SectionGenerationCommitPort {

    /**
     * Atomically verifies the current requirement, outline, their confirmations and section baseline,
     * stores the draft linked to the attempt, and changes the attempt to SUCCEEDED.
     * No write may be retained when this returns false.
     */
    boolean commitIfCurrent(
            GenerationKey key,
            UUID attemptId,
            SectionVersion sectionVersion,
            GenerationAttempt succeededAttempt,
            long expectedAttemptRevision);
}
