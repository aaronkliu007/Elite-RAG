package com.yunjihai.rag.retrieval.projection;

import com.yunjihai.rag.retrieval.domain.SourceLocator;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ProjectionNode(
        String indexDocumentId,
        UUID candidateId,
        String nodeId,
        String kind,
        String bodyText,
        List<String> headingPath,
        int stableOrder,
        SourceLocator locator,
        Double confidence) {

    public ProjectionNode {
        Objects.requireNonNull(indexDocumentId);
        Objects.requireNonNull(candidateId);
        Objects.requireNonNull(nodeId);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(bodyText);
        headingPath = List.copyOf(Objects.requireNonNull(headingPath));
        Objects.requireNonNull(locator);
        if (indexDocumentId.isBlank() || nodeId.isBlank() || kind.isBlank() || bodyText.isBlank()
                || headingPath.stream().anyMatch(heading -> heading == null || heading.isBlank())
                || stableOrder < 0 || confidence != null && (!Double.isFinite(confidence)
                    || confidence < 0 || confidence > 1)) {
            throw new IllegalArgumentException("投影节点无效");
        }
    }
}
