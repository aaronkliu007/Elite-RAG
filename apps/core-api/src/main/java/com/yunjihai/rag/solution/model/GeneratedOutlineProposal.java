package com.yunjihai.rag.solution.model;

import com.yunjihai.rag.solution.domain.OutlineNode;

import java.util.List;
import java.util.Objects;

public record GeneratedOutlineProposal(List<OutlineNode> nodes) {

    public GeneratedOutlineProposal {
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("outline proposal must not be empty");
        }
    }

    @Override
    public String toString() {
        return "GeneratedOutlineProposal[nodeCount=" + nodes.size() + "]";
    }
}
