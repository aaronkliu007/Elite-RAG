package com.yunjihai.rag.solution.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RequirementItem(
        UUID itemId,
        RequirementCategory category,
        String text,
        List<RequirementSource> sources) {

    public RequirementItem {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(category, "category");
        text = SolutionText.required(text, "text");
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (category != RequirementCategory.UNKNOWN && sources.isEmpty()) {
            throw new IllegalArgumentException("known requirement requires a source");
        }
    }
}
