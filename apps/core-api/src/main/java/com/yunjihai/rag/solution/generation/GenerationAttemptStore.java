package com.yunjihai.rag.solution.generation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public interface GenerationAttemptStore {

    /** Persistence implementations should return their authoritative clock for lease decisions. */
    default Instant authoritativeNow(Instant fallback) {
        return Objects.requireNonNull(fallback);
    }

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
