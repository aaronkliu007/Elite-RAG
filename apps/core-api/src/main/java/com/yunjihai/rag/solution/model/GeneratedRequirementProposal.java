package com.yunjihai.rag.solution.model;

import com.yunjihai.rag.solution.domain.RequirementSource;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record GeneratedRequirementProposal(List<Item> items) {

    public GeneratedRequirementProposal {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    @Override
    public String toString() {
        return "GeneratedRequirementProposal[itemCount=" + items.size() + "]";
    }

    public record Item(
            UUID itemId,
            String category,
            String text,
            List<RequirementSource> sources) {

        public Item {
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        }

        @Override
        public String toString() {
            return "Item[itemId=" + itemId + ", category=" + category
                    + ", sourceCount=" + sources.size() + "]";
        }
    }
}
