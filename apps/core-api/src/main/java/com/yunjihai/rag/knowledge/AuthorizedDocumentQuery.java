package com.yunjihai.rag.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface AuthorizedDocumentQuery {

    List<DocumentAggregate> listAuthorized(UUID userId, UUID spaceId, Instant at);
}
