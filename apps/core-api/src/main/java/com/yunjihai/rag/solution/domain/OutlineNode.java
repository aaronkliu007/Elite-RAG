package com.yunjihai.rag.solution.domain;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record OutlineNode(
        UUID sectionId,
        String title,
        int level,
        int order,
        boolean required,
        String objective,
        String retrievalHint,
        Set<RiskType> riskTypes) {

    public OutlineNode {
        Objects.requireNonNull(sectionId, "sectionId");
        title = SolutionText.required(title, "title");
        if (level < 1 || level > 4) {
            throw new IllegalArgumentException("level must be between 1 and 4");
        }
        if (order < 1) {
            throw new IllegalArgumentException("order must be positive");
        }
        objective = SolutionText.required(objective, "objective");
        retrievalHint = SolutionText.required(retrievalHint, "retrievalHint");
        riskTypes = Set.copyOf(Objects.requireNonNull(riskTypes, "riskTypes"));
    }
}
