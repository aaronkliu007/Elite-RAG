package com.yunjihai.rag.solution.generation;

import com.yunjihai.rag.solution.model.PromptRef;

import java.time.Duration;
import java.util.Objects;

public record GenerationPolicy(
        int requestedEvidenceCount,
        int minimumEvidenceCount,
        PromptRef sectionPrompt,
        Duration modelTimeout,
        Duration recoveryAfter,
        int maxExecutions) {

    public GenerationPolicy {
        if (requestedEvidenceCount < 1
                || minimumEvidenceCount < 1
                || minimumEvidenceCount > requestedEvidenceCount) {
            throw new IllegalArgumentException("invalid generation evidence limits");
        }
        Objects.requireNonNull(sectionPrompt, "sectionPrompt");
        Objects.requireNonNull(modelTimeout, "modelTimeout");
        if (modelTimeout.isZero() || modelTimeout.isNegative()) {
            throw new IllegalArgumentException("model timeout must be positive");
        }
        Objects.requireNonNull(recoveryAfter, "recoveryAfter");
        if (recoveryAfter.isZero() || recoveryAfter.isNegative() || maxExecutions < 1) {
            throw new IllegalArgumentException("invalid generation recovery policy");
        }
    }

    public GenerationPolicy(int requestedEvidenceCount, int minimumEvidenceCount,
            PromptRef sectionPrompt, Duration modelTimeout) {
        this(requestedEvidenceCount, minimumEvidenceCount, sectionPrompt, modelTimeout,
                Duration.ofMinutes(2), 3);
    }

    public static GenerationPolicy defaults() {
        return new GenerationPolicy(
                8,
                1,
                new PromptRef("solution-section", "v1"),
                Duration.ofSeconds(60), Duration.ofMinutes(2), 3);
    }
}
