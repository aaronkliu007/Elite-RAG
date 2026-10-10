package com.yunjihai.rag.retrieval.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record RetrievalQuery(
        UUID requestId,
        UUID userId,
        Set<UUID> spaceIds,
        String queryText,
        Map<String, String> metadataFilter,
        int requestedEvidenceCount) {

    public RetrievalQuery {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        spaceIds = DomainValidation.immutableSpaceIds(spaceIds);
        queryText = DomainValidation.normalizeQuery(queryText);
        if (queryText.isEmpty() || DomainValidation.codePointLength(queryText) > 2000) {
            throw new IllegalArgumentException("query text length is invalid");
        }
        metadataFilter = DomainValidation.immutableMetadata(metadataFilter);
        if (requestedEvidenceCount < 8 || requestedEvidenceCount > 15) {
            throw new IllegalArgumentException("requested evidence count must be between 8 and 15");
        }
    }
}
