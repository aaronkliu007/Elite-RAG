package com.yunjihai.rag.solution.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OutlineVersion(
        UUID id,
        long number,
        List<OutlineNode> nodes,
        UUID createdBy,
        Instant createdAt) {

    public OutlineVersion {
        Objects.requireNonNull(id, "id");
        if (number < 1) {
            throw new IllegalArgumentException("number must be positive");
        }
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        if (nodes.isEmpty() || nodes.stream().noneMatch(OutlineNode::required)) {
            throw new IllegalArgumentException("outline requires at least one required node");
        }
        var ids = new HashSet<UUID>();
        for (int index = 0; index < nodes.size(); index++) {
            var node = nodes.get(index);
            if (!ids.add(node.sectionId())) {
                throw new IllegalArgumentException("duplicate section id");
            }
            if (node.order() != index + 1) {
                throw new IllegalArgumentException("outline order must be contiguous");
            }
        }
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
