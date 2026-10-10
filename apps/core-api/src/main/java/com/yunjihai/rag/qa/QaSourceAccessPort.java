package com.yunjihai.rag.qa;

import com.yunjihai.rag.retrieval.domain.SourceLocator;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface QaSourceAccessPort {
    record QaSource(
            UUID requestId,
            UUID evidenceId,
            UUID spaceId,
            UUID documentId,
            UUID versionId,
            String nodeId,
            String title,
            SourceLocator locator) {
        public QaSource {
            Objects.requireNonNull(requestId, "requestId must not be null");
            Objects.requireNonNull(evidenceId, "evidenceId must not be null");
            Objects.requireNonNull(spaceId, "spaceId must not be null");
            Objects.requireNonNull(documentId, "documentId must not be null");
            Objects.requireNonNull(versionId, "versionId must not be null");
            if (nodeId == null || nodeId.isBlank() || title == null || title.isBlank()) {
                throw new IllegalArgumentException("source text fields must not be blank");
            }
            Objects.requireNonNull(locator, "locator must not be null");
        }
    }

    Optional<QaSource> findAuthorized(UUID requestId, UUID evidenceId, UUID actorId, Instant at);
}
