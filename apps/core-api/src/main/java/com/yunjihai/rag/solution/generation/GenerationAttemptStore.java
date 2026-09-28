package com.yunjihai.rag.solution.generation;

import java.util.Optional;

public interface GenerationAttemptStore {

    Optional<GenerationAttempt> findByClientKey(String clientKey);

    /**
     * Atomically registers the client key and reports whether this caller owns execution.
     * Implementations must use a unique constraint or an equivalent compare-and-set operation.
     */
    GenerationAttemptRegistration register(GenerationAttempt attempt);

    default GenerationAttempt insertIfAbsent(GenerationAttempt attempt) {
        return register(attempt).attempt();
    }

    void replace(GenerationAttempt next, long expectedRevision);
}
