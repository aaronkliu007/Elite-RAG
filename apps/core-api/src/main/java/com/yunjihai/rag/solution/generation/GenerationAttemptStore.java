package com.yunjihai.rag.solution.generation;

import java.util.Optional;

public interface GenerationAttemptStore {

    Optional<GenerationAttempt> findByClientKey(String clientKey);

    GenerationAttempt insertIfAbsent(GenerationAttempt attempt);

    void replace(GenerationAttempt next, long expectedRevision);
}
