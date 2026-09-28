package com.yunjihai.rag.solution.generation;

import com.yunjihai.rag.solution.domain.SectionVersion;

import java.util.Objects;
import java.util.Optional;

public record SectionGenerationResult(
        GenerationAttempt attempt,
        Optional<SectionVersion> sectionVersion) {

    public SectionGenerationResult {
        Objects.requireNonNull(attempt, "attempt");
        sectionVersion = Objects.requireNonNull(sectionVersion, "sectionVersion");
        if (attempt.status() == GenerationStatus.SUCCEEDED && sectionVersion.isEmpty()) {
            throw new IllegalArgumentException("successful generation requires a section version");
        }
        if (attempt.status() != GenerationStatus.SUCCEEDED && sectionVersion.isPresent()) {
            throw new IllegalArgumentException("unfinished generation cannot expose a section version");
        }
    }
}
