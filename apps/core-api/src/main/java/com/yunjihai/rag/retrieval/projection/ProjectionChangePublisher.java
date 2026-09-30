package com.yunjihai.rag.retrieval.projection;

import com.yunjihai.rag.knowledge.DocumentAggregate;

import java.time.Instant;
import java.util.UUID;

public interface ProjectionChangePublisher {
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
