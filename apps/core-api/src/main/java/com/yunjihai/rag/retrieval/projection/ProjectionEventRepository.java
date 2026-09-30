package com.yunjihai.rag.retrieval.projection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ProjectionEventRepository {
    long enqueueDocument(UUID spaceId, UUID documentId, UUID versionId, long documentRevision, Instant at);

    long enqueueSpace(UUID spaceId, Instant at);

    List<ProjectionLease> claimBatch(String workerId, int limit, Instant now, Instant leaseUntil);

    void renew(UUID eventId, UUID leaseToken, Instant at, Instant leaseUntil);

    void checkpoint(UUID eventId, UUID leaseToken, UUID progressDocumentId, Instant at, Instant leaseUntil);

    void complete(UUID eventId, UUID leaseToken, Instant at);

    void retry(UUID eventId, UUID leaseToken, String errorCode, Instant nextAttemptAt, Instant at);

    void fail(UUID eventId, UUID leaseToken, String errorCode, Instant at);

    long currentSequence();

    List<ProjectionEvent> eventsAfter(long sequence, int limit);
}
