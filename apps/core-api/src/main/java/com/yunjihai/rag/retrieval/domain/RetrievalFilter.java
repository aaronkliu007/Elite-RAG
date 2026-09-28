package com.yunjihai.rag.retrieval.domain;

import com.yunjihai.rag.access.PrincipalRef;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record RetrievalFilter(
        UUID spaceId,
        Set<PrincipalRef> effectivePrincipals,
        boolean currentPublishedOnly,
        Instant effectiveAt,
        Map<String, String> metadataFilter) {

    public RetrievalFilter {
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        effectivePrincipals = Set.copyOf(
                Objects.requireNonNull(effectivePrincipals, "effectivePrincipals must not be null"));
        if (!currentPublishedOnly) {
            throw new IllegalArgumentException("retrieval must target current published versions");
        }
        Objects.requireNonNull(effectiveAt, "effectiveAt must not be null");
        metadataFilter = DomainValidation.immutableMetadata(metadataFilter);
    }
}
