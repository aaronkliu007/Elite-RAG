package com.yunjihai.rag.solution.generation;

import java.util.Objects;

public record GenerationAttemptRegistration(GenerationAttempt attempt, boolean created) {

    public GenerationAttemptRegistration {
        Objects.requireNonNull(attempt, "attempt");
    }
}
