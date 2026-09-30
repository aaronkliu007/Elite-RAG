package com.yunjihai.rag.retrieval.projection;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        int completed = 0;
        for (int claimed = 0; claimed < policy.claimBatchSize(); claimed++) {
            Instant claimedAt = clock.instant();
            List<ProjectionLease> leases = events.claimBatch(
                    workerId, 1, claimedAt, claimedAt.plus(policy.leaseDuration()));
            if (leases.isEmpty()) break;
            if (leases.size() != 1) {
                throw new IllegalStateException("PROJECTION_CLAIM_LIMIT_VIOLATED");
            }
            if (processLease(leases.getFirst(), targetIndex)) completed++;
        }
        return completed;
    }

    private boolean processLease(ProjectionLease lease, String targetIndex) {
        if (lease.attempt() > policy.maxAttempts()) {
            try {
                events.fail(lease.eventId(), lease.leaseToken(), RETRY_EXHAUSTED, clock.instant());
            } catch (ProjectionLeaseConflictException staleLease) {
                return false;
            }
            return false;
        }
        try (var heartbeat = new LeaseHeartbeat(lease)) {
            heartbeat.renewNow();
            switch (lease.scope()) {
                case DOCUMENT -> projectDocument(
                        lease.spaceId(), lease.documentId(), lease.sequence(), targetIndex, heartbeat);
                case SPACE -> projectSpace(lease, targetIndex, heartbeat);
            }
            heartbeat.requireOwned();
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

    private void projectSpace(ProjectionLease lease, String targetIndex, LeaseHeartbeat heartbeat) {
        var cursor = lease.progressDocumentId();
        while (true) {
            heartbeat.renewNow();
            var page = source.pageSpace(
                    lease.spaceId(), cursor, policy.spacePageSize(), lease.sequence());
            if (page.isEmpty()) return;
            for (RetrievalProjectionSnapshot snapshot : page) {
                projectDocument(
                        lease.spaceId(), snapshot.documentId(), lease.sequence(), targetIndex, heartbeat);
            }
            cursor = page.getLast().documentId();
            Instant checkpointedAt = clock.instant();
            events.checkpoint(lease.eventId(), lease.leaseToken(), cursor, checkpointedAt,
                    checkpointedAt.plus(policy.leaseDuration()));
        }
    }

    private void projectDocument(
            java.util.UUID spaceId, java.util.UUID documentId, long sequence, String targetIndex,
            LeaseHeartbeat heartbeat) {
        heartbeat.renewNow();
        documentLock.withDocumentLock(documentId, () -> {
            writer.apply(targetIndex, source.load(spaceId, documentId, sequence));
            return null;
        });
        heartbeat.requireOwned();
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

    private final class LeaseHeartbeat implements AutoCloseable {
        private final ProjectionLease lease;
        private final ScheduledExecutorService scheduler;
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

        private LeaseHeartbeat(ProjectionLease lease) {
            this.lease = lease;
            this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "projection-lease-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
            long interval = Math.max(1L, policy.leaseDuration().toNanos() / 3L);
            scheduler.scheduleAtFixedRate(this::renewInBackground,
                    interval, interval, TimeUnit.NANOSECONDS);
        }

        private void renewNow() {
            requireOwned();
            Instant renewedAt = clock.instant();
            try {
                events.renew(lease.eventId(), lease.leaseToken(), renewedAt,
                        renewedAt.plus(policy.leaseDuration()));
            } catch (RuntimeException lost) {
                failure.compareAndSet(null, lost);
                throw lost;
            }
        }

        private void renewInBackground() {
            if (failure.get() != null) return;
            try {
                Instant renewedAt = clock.instant();
                events.renew(lease.eventId(), lease.leaseToken(), renewedAt,
                        renewedAt.plus(policy.leaseDuration()));
            } catch (RuntimeException lost) {
                failure.compareAndSet(null, lost);
            }
        }

        private void requireOwned() {
            RuntimeException lost = failure.get();
            if (lost != null) throw lost;
        }

        @Override
        public void close() {
            scheduler.shutdownNow();
        }
    }
}
