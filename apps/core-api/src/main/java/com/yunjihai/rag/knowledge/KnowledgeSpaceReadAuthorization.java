package com.yunjihai.rag.knowledge;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;

import java.time.Instant;
import java.util.UUID;

@FunctionalInterface
public interface KnowledgeSpaceReadAuthorization {

    void requireRead(UUID userId, UUID spaceId, Instant at);

    static KnowledgeSpaceReadAuthorization denyAll() {
        return (userId, spaceId, at) -> {
            throw new AccessDeniedAsNotFoundException();
        };
    }
}
