package com.yunjihai.rag.retrieval.selection;

import com.yunjihai.rag.knowledge.DocumentVersionRef;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@FunctionalInterface
public interface RetrievalAuthorizationPort {

    Set<DocumentVersionRef> authorize(
            UUID userId,
            UUID spaceId,
            Set<DocumentVersionRef> requested,
            Instant checkedAt);
}
