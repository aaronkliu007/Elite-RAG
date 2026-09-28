package com.yunjihai.rag.solution.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record GeneratedOutlineProposal(List<Node> nodes) {

    public GeneratedOutlineProposal {
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
    }

    @Override
    public String toString() {
        return "GeneratedOutlineProposal[nodeCount=" + nodes.size() + "]";
    }

    public record Node(
            UUID sectionId,
            String title,
            int level,
            int order,
            boolean required,
            String objective,
            String retrievalHint,
            Set<String> riskTypes) {

        public Node {
            riskTypes = Set.copyOf(Objects.requireNonNull(riskTypes, "riskTypes"));
        }

        @Override
        public String toString() {
            return "Node[sectionId=" + sectionId + ", level=" + level
                    + ", order=" + order + ", required=" + required
                    + ", riskTypeCount=" + riskTypes.size() + "]";
        }
    }
}
