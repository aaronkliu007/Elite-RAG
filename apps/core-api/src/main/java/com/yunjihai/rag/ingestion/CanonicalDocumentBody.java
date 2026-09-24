package com.yunjihai.rag.ingestion;

import java.util.List;

public record CanonicalDocumentBody(
        String sourceFilename,
        String mediaType,
        int pageCount,
        List<CanonicalNode> nodes) {
}
