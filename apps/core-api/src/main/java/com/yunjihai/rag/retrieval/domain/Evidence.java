package com.yunjihai.rag.retrieval.domain;

import java.util.Objects;
import java.util.UUID;

public record Evidence(
        UUID evidenceId,
        UUID documentId,
        UUID versionId,
        String nodeId,
        String title,
        String excerpt,
        double score,
        SourceLocator locator) {

    public Evidence {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(versionId, "versionId must not be null");
        requireText(nodeId, 200, "nodeId");
        requireText(title, 500, "title");
        requireText(excerpt, 1200, "excerpt");
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite");
        }
        Objects.requireNonNull(locator, "locator must not be null");
    }

    private static void requireText(String value, int maxCodePoints, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank() || DomainValidation.codePointLength(value) > maxCodePoints) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
    }
}
