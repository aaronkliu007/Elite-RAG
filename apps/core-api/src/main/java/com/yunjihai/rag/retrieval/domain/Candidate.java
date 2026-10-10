package com.yunjihai.rag.retrieval.domain;

import java.util.Objects;
import java.util.UUID;

public record Candidate(
        UUID spaceId,
        UUID candidateId,
        UUID documentId,
        UUID versionId,
        String nodeId,
        String title,
        String text,
        SourceLocator locator,
        RecallSource source,
        int sourceRank,
        Double sourceScore) {

    public Candidate {
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(candidateId, "candidateId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(versionId, "versionId must not be null");
        requireText(nodeId, 200, "nodeId");
        requireText(title, 500, "title");
        requireText(text, 20_000, "text");
        Objects.requireNonNull(locator, "locator must not be null");
        Objects.requireNonNull(source, "source must not be null");
        if (sourceRank < 1) {
            throw new IllegalArgumentException("source rank must start at one");
        }
        if (sourceScore != null && !Double.isFinite(sourceScore)) {
            throw new IllegalArgumentException("source score must be finite");
        }
    }

    private static void requireText(String value, int maxCodePoints, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        int length = DomainValidation.codePointLength(value);
        if (value.isBlank() || length > maxCodePoints) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
    }
}
