package com.yunjihai.rag.retrieval.projection;

import com.yunjihai.rag.knowledge.DocumentAggregate;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public interface ProjectionChangePublisher {
    default <T> T withDocumentChangeLock(UUID documentId, Supplier<T> action) {
        Objects.requireNonNull(documentId);
        return Objects.requireNonNull(action).get();
    }

    default <T> T withSpaceChangeLock(UUID spaceId, Supplier<T> action) {
        Objects.requireNonNull(spaceId);
        return Objects.requireNonNull(action).get();
    }

    void documentChanged(DocumentAggregate document, UUID versionId, Instant at);

    void spaceAclChanged(UUID spaceId, Instant at);

    void documentAclChanged(UUID spaceId, UUID documentId, Instant at);

    static ProjectionChangePublisher noop() {
        return new ProjectionChangePublisher() {
            @Override
            public void documentChanged(DocumentAggregate document, UUID versionId, Instant at) { }

            @Override
            public void spaceAclChanged(UUID spaceId, Instant at) { }

            @Override
            public void documentAclChanged(UUID spaceId, UUID documentId, Instant at) { }
        };
    }
}
