package com.yunjihai.rag.retrieval.projection;

import java.util.List;
import java.util.UUID;

public interface RetrievalProjectionSource {
    RetrievalProjectionSnapshot load(
            UUID spaceId, UUID documentId, long projectionSequence);

    List<RetrievalProjectionSnapshot> pageSpace(
            UUID spaceId, UUID afterDocumentId, int limit, long projectionSequence);

    List<RetrievalProjectionSnapshot> pageAll(
            UUID afterDocumentId, int limit, long projectionSequence);
}
