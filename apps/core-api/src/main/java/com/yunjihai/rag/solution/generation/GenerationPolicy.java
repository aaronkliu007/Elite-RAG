package com.yunjihai.rag.solution.generation;

import com.yunjihai.rag.solution.model.PromptRef;

import java.time.Duration;
import java.util.Objects;

public record GenerationPolicy(
        int requestedEvidenceCount,
        int minimumEvidenceCount,
        PromptRef sectionPrompt,
        Duration modelTimeout) {

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
    }

    public static GenerationPolicy defaults() {
        return new GenerationPolicy(
                8,
                1,
                new PromptRef("solution-section", "v1"),
                Duration.ofSeconds(60));
    }
}
