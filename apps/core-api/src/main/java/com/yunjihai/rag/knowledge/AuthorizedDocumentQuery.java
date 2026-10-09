package com.yunjihai.rag.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface AuthorizedDocumentQuery {

    List<DocumentAggregate> listAuthorized(UUID userId, UUID spaceId, Instant at);

    default List<DocumentAggregate> listAuthorized(
            UUID userId, UUID spaceId, Instant at, int offset, int limit) {
        return listAuthorized(userId, spaceId, at).stream()
                .skip(offset)
                .limit(limit)
                .toList();
    }
}
