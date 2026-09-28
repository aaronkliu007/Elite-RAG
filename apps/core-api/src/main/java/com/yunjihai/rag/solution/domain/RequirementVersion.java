package com.yunjihai.rag.solution.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RequirementVersion(
        UUID id,
        long number,
        List<RequirementItem> items,
        UUID createdBy,
        Instant createdAt,
        String changeReason) {

    public RequirementVersion {
        Objects.requireNonNull(id, "id");
        if (number < 1) {
            throw new IllegalArgumentException("number must be positive");
        }
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (items.isEmpty()) {
            throw new IllegalArgumentException("requirements must not be empty");
        }
        var ids = new HashSet<UUID>();
        if (items.stream().map(RequirementItem::itemId).anyMatch(itemId -> !ids.add(itemId))) {
            throw new IllegalArgumentException("duplicate requirement item id");
        }
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        changeReason = SolutionText.required(changeReason, "changeReason");
    }
}
