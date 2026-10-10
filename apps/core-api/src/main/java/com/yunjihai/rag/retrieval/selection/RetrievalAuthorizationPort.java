package com.yunjihai.rag.retrieval.selection;

import com.yunjihai.rag.knowledge.DocumentVersionRef;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@FunctionalInterface
public interface RetrievalAuthorizationPort {

    Set<DocumentVersionRef> authorize(
            UUID userId,
            Map<UUID, Set<DocumentVersionRef>> versionsBySpace,
            Instant checkedAt);
}
