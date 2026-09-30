package com.yunjihai.rag.retrieval.projection;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class ProjectionWorker {
    static final String TEMPORARY_ERROR = "PROJECTION_TEMPORARY_FAILURE";
    static final String RETRY_EXHAUSTED = "PROJECTION_RETRY_EXHAUSTED";
    static final String INVALID_PROJECTION = "INVALID_RETRIEVAL_PROJECTION";

    private final ProjectionEventRepository events;
    private final RetrievalProjectionSource source;
    private final ProjectionDocumentLock documentLock;
    private final SearchProjectionWriter writer;
    private final ProjectionWorkerPolicy policy;
    private final Clock clock;

    public ProjectionWorker(
            ProjectionEventRepository events,
            RetrievalProjectionSource source,
            ProjectionDocumentLock documentLock,
            SearchProjectionWriter writer,
            ProjectionWorkerPolicy policy,
            Clock clock) {
        this.events = Objects.requireNonNull(events);
        this.source = Objects.requireNonNull(source);
        this.documentLock = Objects.requireNonNull(documentLock);
        this.writer = Objects.requireNonNull(writer);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
    }

    public int runOnce(String workerId, String targetIndex) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("worker id must not be blank");
        }
        if (targetIndex == null || targetIndex.isBlank()) {
            throw new IllegalArgumentException("target index must not be blank");
        }
        Instant claimedAt = clock.instant();
        List<ProjectionLease> leases = events.claimBatch(
                workerId, policy.claimBatchSize(), claimedAt,
                claimedAt.plus(policy.leaseDuration()));
        int completed = 0;
        for (ProjectionLease lease : leases) {
            if (processLease(lease, targetIndex)) completed++;
        }
        return completed;
    }

    private boolean processLease(ProjectionLease lease, String targetIndex) {
        try {
            switch (lease.scope()) {
                case DOCUMENT -> projectDocument(
                        lease.spaceId(), lease.documentId(), lease.sequence(), targetIndex);
                case SPACE -> projectSpace(lease, targetIndex);
            }
            events.complete(lease.eventId(), lease.leaseToken(), clock.instant());
            return true;
        } catch (ProjectionLeaseConflictException staleLease) {
            return false;
        } catch (RuntimeException failure) {
            restoreInterrupt(failure);
            transitionFailure(lease, failure);
            return false;
        }
    }

    private void projectSpace(ProjectionLease lease, String targetIndex) {
        var cursor = lease.progressDocumentId();
        while (true) {
            var page = source.pageSpace(
                    lease.spaceId(), cursor, policy.spacePageSize(), lease.sequence());
            if (page.isEmpty()) return;
            for (RetrievalProjectionSnapshot snapshot : page) {
                projectDocument(
                        lease.spaceId(), snapshot.documentId(), lease.sequence(), targetIndex);
            }
            cursor = page.getLast().documentId();
            events.checkpoint(lease.eventId(), lease.leaseToken(), cursor, clock.instant());
        }
    }

    private void projectDocument(
            java.util.UUID spaceId, java.util.UUID documentId, long sequence, String targetIndex) {
        documentLock.withDocumentLock(documentId, () -> {
            writer.apply(targetIndex, source.load(spaceId, documentId, sequence));
            return null;
        });
    }

    private void transitionFailure(ProjectionLease lease, RuntimeException failure) {
        Instant failedAt = clock.instant();
        try {
            if (isInvalidProjection(failure)) {
                events.fail(lease.eventId(), lease.leaseToken(), INVALID_PROJECTION, failedAt);
            } else if (lease.attempt() >= policy.maxAttempts()) {
                events.fail(lease.eventId(), lease.leaseToken(), RETRY_EXHAUSTED, failedAt);
            } else {
                events.retry(lease.eventId(), lease.leaseToken(), TEMPORARY_ERROR,
                        failedAt.plus(policy.retryDelay(lease.attempt())), failedAt);
            }
        } catch (ProjectionLeaseConflictException staleLease) {
            // Another worker owns the expired lease; its state is authoritative.
        }
    }

    private static boolean isInvalidProjection(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (INVALID_PROJECTION.equals(current.getMessage())) return true;
        }
        return false;
    }

    private static void restoreInterrupt(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
