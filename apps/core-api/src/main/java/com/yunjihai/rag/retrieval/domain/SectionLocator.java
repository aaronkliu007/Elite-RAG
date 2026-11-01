package com.yunjihai.rag.retrieval.domain;

import java.util.List;
import java.util.Objects;

public record SectionLocator(List<String> headingPath, String nodeId) implements SourceLocator {

    public SectionLocator {
        headingPath = List.copyOf(Objects.requireNonNull(headingPath, "headingPath must not be null"));
        if (headingPath.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("heading path contains a blank heading");
        }
        nodeId = nodeId == null ? null : nodeId.strip();
        if (headingPath.isEmpty() && (nodeId == null || nodeId.isEmpty())) {
            throw new IllegalArgumentException("section location requires a heading or node id");
        }
    }
}
