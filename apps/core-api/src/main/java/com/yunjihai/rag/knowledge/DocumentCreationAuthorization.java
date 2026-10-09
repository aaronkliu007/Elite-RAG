package com.yunjihai.rag.knowledge;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;

import java.util.Objects;
import java.util.UUID;

@FunctionalInterface
public interface DocumentCreationAuthorization {
    void require(UUID actorId, UUID spaceId);

    static DocumentCreationAuthorization backedBy(
            KnowledgeSpaceRepository spaces,
            KnowledgeWriteAuthorization roles) {
        Objects.requireNonNull(spaces);
        Objects.requireNonNull(roles);
        return (actorId, spaceId) -> {
            if (!spaces.lockForDocumentCreation(spaceId)) {
                throw new AccessDeniedAsNotFoundException();
            }
            roles.require(
                    actorId, spaceId, null,
                    KnowledgeWriteAuthorization.Action.CREATE_DOCUMENT);
        };
    }

    static DocumentCreationAuthorization denyAll() {
        return (actorId, spaceId) -> {
            throw new AccessDeniedAsNotFoundException();
        };
    }
}
