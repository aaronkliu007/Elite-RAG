package com.yunjihai.rag.knowledge;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;

import java.util.UUID;

@FunctionalInterface
public interface KnowledgeWriteAuthorization {

    void require(UUID actorId, UUID spaceId, UUID documentId, Action action);

    enum Action {
        CREATE_SPACE,
        CREATE_DOCUMENT,
        EDIT_DOCUMENT,
        SUBMIT_FOR_REVIEW,
        REVIEW_DOCUMENT,
        PUBLISH_DOCUMENT,
        ARCHIVE_DOCUMENT,
        MANAGE_ACL
    }

    static KnowledgeWriteAuthorization allowAll() {
        return (actorId, spaceId, documentId, action) -> { };
    }

    static KnowledgeWriteAuthorization denyAll() {
        return (actorId, spaceId, documentId, action) -> {
            throw new AccessDeniedAsNotFoundException();
        };
    }
}
