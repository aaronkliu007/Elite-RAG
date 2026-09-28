package com.yunjihai.rag.solution.model;

import com.yunjihai.rag.solution.domain.RequirementItem;

import java.util.List;
import java.util.Objects;

public record GeneratedRequirementProposal(List<RequirementItem> items) {

    public GeneratedRequirementProposal {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (items.isEmpty()) {
            throw new IllegalArgumentException("requirement proposal must not be empty");
        }
    }

    @Override
    public String toString() {
        return "GeneratedRequirementProposal[itemCount=" + items.size() + "]";
    }
}
