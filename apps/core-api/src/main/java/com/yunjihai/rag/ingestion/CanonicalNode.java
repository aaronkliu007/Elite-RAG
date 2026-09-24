package com.yunjihai.rag.ingestion;

import java.util.List;

public record CanonicalNode(
        String nodeId,
        String kind,
        String text,
        String parentNodeId,
        Integer page,
        CanonicalBoundingBox bbox,
        List<CanonicalTableCell> tableCells,
        Double confidence) {
}
