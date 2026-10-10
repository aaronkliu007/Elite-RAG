package com.yunjihai.rag.qa.domain;

import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.SourceLocator;

import java.util.Objects;
import java.util.UUID;

public record QaEvidence(
        UUID evidenceId,
        UUID spaceId,
        UUID documentId,
        UUID versionId,
        String nodeId,
        String title,
        String excerpt,
        double score,
        SourceLocator locator) {

    public QaEvidence {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(versionId, "versionId must not be null");
        requireText(nodeId, 200, "nodeId");
        requireText(title, 500, "title");
        requireText(excerpt, 1_200, "excerpt");
        if (!Double.isFinite(score)) throw new IllegalArgumentException("score must be finite");
        Objects.requireNonNull(locator, "locator must not be null");
    }

    public static QaEvidence from(Evidence evidence) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        return new QaEvidence(
                evidence.evidenceId(), evidence.spaceId(), evidence.documentId(), evidence.versionId(),
                evidence.nodeId(), evidence.title(), evidence.excerpt(), evidence.score(), evidence.locator());
    }

    private static void requireText(String value, int maxCodePoints, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank() || value.codePointCount(0, value.length()) > maxCodePoints) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
    }
}
