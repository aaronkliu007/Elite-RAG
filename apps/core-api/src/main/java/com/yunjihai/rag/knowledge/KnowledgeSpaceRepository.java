package com.yunjihai.rag.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeSpaceRepository {

    void insert(KnowledgeSpace space);

    default Optional<KnowledgeSpace> findById(UUID spaceId) {
        throw new UnsupportedOperationException("findById is unavailable");
    }

    /** Holds a key-share lock until the current transaction ends when supported. */
    default boolean lockForDocumentCreation(UUID spaceId) {
        return findById(spaceId).isPresent();
    }

    default List<KnowledgeSpace> listAuthorized(UUID userId, int offset, int limit) {
        throw new UnsupportedOperationException("listAuthorized is unavailable");
    }

    default boolean replace(long expectedRevision, KnowledgeSpace space) {
        throw new UnsupportedOperationException("replace is unavailable");
    }
}
