package com.yunjihai.rag.retrieval.web;

import java.util.Map;
import java.util.UUID;

public record SearchRequest(
        UUID spaceId,
        String queryText,
        Map<String, String> metadataFilter,
        Integer requestedEvidenceCount) {
}
