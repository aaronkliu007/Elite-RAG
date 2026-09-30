package com.yunjihai.rag.retrieval.projection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionWorkerTest {
    private static final Instant NOW = Instant.parse("2026-09-30T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID DOCUMENT_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DOCUMENT_3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final ProjectionWorkerPolicy POLICY = new ProjectionWorkerPolicy(
            5, 2, Duration.ofMinutes(1), 3, Duration.ofSeconds(5), Duration.ofMinutes(1));

    @AfterEach
    void clearInterruptedFlag() {
        Thread.interrupted();
    }

    @Test
    void processesDocumentInsideLockAndCompletesLease() {
        var repository = new FakeRepository(documentLease(1, 1));
        var source = new FakeSource(List.of(deletion(DOCUMENT_1, 1)));
        var locks = new RecordingLock();
        var writer = new RecordingWriter();
        var worker = new ProjectionWorker(repository, source, locks, writer, POLICY, CLOCK);

        assertEquals(1, worker.runOnce("worker-a", "rag-chunks-write"));

        assertEquals(List.of(DOCUMENT_1), source.loaded);
        assertEquals(List.of(DOCUMENT_1), locks.documents);
        assertEquals(List.of(DOCUMENT_1), writer.documents);
        assertEquals(1, repository.completed.size());
        assertTrue(repository.retried.isEmpty());
        assertEquals(NOW.plus(POLICY.leaseDuration()), repository.claimedLeaseUntil);
        assertEquals(List.of(1, 1), repository.claimLimits);
    }

    @Test
    void checkpointsEveryCompletedSpacePageAndResumesFromLeaseCursor() {
        var repository = new FakeRepository(spaceLease(8, 1, DOCUMENT_1));
        var source = new FakeSource(List.of(
                deletion(DOCUMENT_1, 8), deletion(DOCUMENT_2, 8), deletion(DOCUMENT_3, 8)));
        var writer = new RecordingWriter();
        var worker = new ProjectionWorker(
                repository, source, new RecordingLock(), writer, POLICY, CLOCK);

        assertEquals(1, worker.runOnce("worker-a", "rag-chunks-write"));

        assertEquals(List.of(DOCUMENT_1, DOCUMENT_3), source.spaceCursors);
        assertEquals(List.of(DOCUMENT_2, DOCUMENT_3), source.loaded);
        assertEquals(List.of(DOCUMENT_2, DOCUMENT_3), writer.documents);
        assertEquals(List.of(DOCUMENT_3), repository.checkpoints);
        assertEquals(NOW.plus(POLICY.leaseDuration()), repository.checkpointLeaseUntil);
        assertEquals(1, repository.completed.size());
    }

    @Test
    void renewsLeaseBeforeEachBatchItemAndStopsWhenOwnershipWasLost() {
        var first = documentLease(20, 1);
        var second = new ProjectionLease(
                UUID.randomUUID(), 21, ProjectionScope.DOCUMENT, SPACE, DOCUMENT_2, null,
                1L, null, UUID.randomUUID(), 1, "worker-a", NOW.plusSeconds(60));
        var repository = new FakeRepository(first, second);
        repository.failRenewAt = 3;
        var source = new FakeSource(List.of(
                deletion(DOCUMENT_1, 20), deletion(DOCUMENT_2, 21)));
        var writer = new RecordingWriter();
        var worker = new ProjectionWorker(
                repository, source, new RecordingLock(), writer, POLICY, CLOCK);

        assertEquals(1, worker.runOnce("worker-a", "rag-chunks-write"));

        assertEquals(List.of(DOCUMENT_1), source.loaded);
        assertEquals(List.of(DOCUMENT_1), writer.documents);
        assertEquals(1, repository.completed.size());
        assertTrue(repository.retried.isEmpty());
    }

    @Test
    void retainsLastCompletedPageCheckpointWhenLaterPageFails() {
        var repository = new FakeRepository(spaceLease(9, 1, null));
        var source = new FakeSource(List.of(
                deletion(DOCUMENT_1, 9), deletion(DOCUMENT_2, 9), deletion(DOCUMENT_3, 9)));
        var writer = new RecordingWriter();
        writer.failDocument = DOCUMENT_3;
        var worker = new ProjectionWorker(
                repository, source, new RecordingLock(), writer, POLICY, CLOCK);

        assertEquals(0, worker.runOnce("worker-a", "rag-chunks-write"));

        assertEquals(List.of(DOCUMENT_2), repository.checkpoints);
        assertEquals(List.of(DOCUMENT_1, DOCUMENT_2, DOCUMENT_3), source.loaded);
        assertEquals(List.of(DOCUMENT_1, DOCUMENT_2, DOCUMENT_3), writer.documents);
        assertEquals("PROJECTION_TEMPORARY_FAILURE", repository.retryError);
        assertEquals(NOW.plusSeconds(5), repository.retryAt);
    }

    @Test
    void structurallyInvalidProjectionFailsWithoutRetry() {
        var repository = new FakeRepository(documentLease(10, 1));
        var source = new FakeSource(List.of());
        source.loadFailure = new IllegalStateException("INVALID_RETRIEVAL_PROJECTION");
        var worker = new ProjectionWorker(
                repository, source, new RecordingLock(), new RecordingWriter(), POLICY, CLOCK);

        assertEquals(0, worker.runOnce("worker-a", "rag-chunks-write"));

        assertEquals("INVALID_RETRIEVAL_PROJECTION", repository.failedError);
        assertTrue(repository.retried.isEmpty());
    }

    @Test
    void exhaustsBoundedRetriesAndPreservesInterruptSignal() {
        var repository = new FakeRepository(documentLease(11, 3));
        var source = new FakeSource(List.of(deletion(DOCUMENT_1, 11)));
        var writer = new RecordingWriter();
        writer.failure = new IllegalStateException("transport", new InterruptedException("stop"));
        var worker = new ProjectionWorker(
                repository, source, new RecordingLock(), writer, POLICY, CLOCK);

        assertEquals(0, worker.runOnce("worker-a", "rag-chunks-write"));

        assertEquals("PROJECTION_RETRY_EXHAUSTED", repository.failedError);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void staleCompletionLeaseIsNotReclassified() {
        var repository = new FakeRepository(documentLease(12, 1));
        repository.completeFailure = new ProjectionLeaseConflictException();
        var worker = new ProjectionWorker(repository,
                new FakeSource(List.of(deletion(DOCUMENT_1, 12))),
                new RecordingLock(), new RecordingWriter(), POLICY, CLOCK);

        assertEquals(0, worker.runOnce("worker-a", "rag-chunks-write"));

        assertTrue(repository.retried.isEmpty());
        assertTrue(repository.failed.isEmpty());
    }

    @Test
    void validatesWorkerPolicyBounds() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectionWorkerPolicy(
                0, 2, Duration.ofMinutes(1), 3, Duration.ofSeconds(1), Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new ProjectionWorkerPolicy(
                1, 2, Duration.ZERO, 3, Duration.ofSeconds(1), Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new ProjectionWorkerPolicy(
                1, 2, Duration.ofMinutes(1), 0, Duration.ofSeconds(1), Duration.ofMinutes(1)));
        assertFalse(POLICY.retryDelay(3).isNegative());
        assertEquals(Duration.ofSeconds(20), POLICY.retryDelay(3));
    }

    private static RetrievalProjectionSnapshot deletion(UUID documentId, long sequence) {
        return RetrievalProjectionSnapshot.deletion(SPACE, documentId, 1, sequence);
    }

    private static ProjectionLease documentLease(long sequence, int attempt) {
        return lease(sequence, attempt, ProjectionScope.DOCUMENT, DOCUMENT_1, null);
    }

    private static ProjectionLease spaceLease(long sequence, int attempt, UUID cursor) {
        return lease(sequence, attempt, ProjectionScope.SPACE, null, cursor);
    }

    private static ProjectionLease lease(
            long sequence, int attempt, ProjectionScope scope, UUID document, UUID cursor) {
        return new ProjectionLease(
                UUID.randomUUID(), sequence, scope, SPACE, document, null,
                document == null ? null : 1L, cursor, UUID.randomUUID(), attempt,
                "worker-a", NOW.plusSeconds(60));
    }

    private static final class FakeSource implements RetrievalProjectionSource {
        private final List<RetrievalProjectionSnapshot> snapshots;
        private final List<UUID> loaded = new ArrayList<>();
        private final List<UUID> spaceCursors = new ArrayList<>();
        private RuntimeException loadFailure;

        private FakeSource(List<RetrievalProjectionSnapshot> snapshots) {
            this.snapshots = snapshots.stream()
                    .sorted(Comparator.comparing(RetrievalProjectionSnapshot::documentId)).toList();
        }

        @Override
        public RetrievalProjectionSnapshot load(UUID spaceId, UUID documentId, long sequence) {
            loaded.add(documentId);
            if (loadFailure != null) throw loadFailure;
            return snapshots.stream().filter(item -> item.documentId().equals(documentId))
                    .findFirst().orElseGet(() -> deletion(documentId, sequence));
        }

        @Override
        public List<RetrievalProjectionSnapshot> pageSpace(
                UUID spaceId, UUID afterDocumentId, int limit, long sequence) {
            spaceCursors.add(afterDocumentId);
            return snapshots.stream()
                    .filter(item -> afterDocumentId == null
                            || item.documentId().compareTo(afterDocumentId) > 0)
                    .limit(limit).toList();
        }

        @Override
        public List<RetrievalProjectionSnapshot> pageAll(UUID cursor, int limit, long sequence) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingLock implements ProjectionDocumentLock {
        private final List<UUID> documents = new ArrayList<>();

        @Override
        public <T> T withDocumentLock(UUID documentId, Supplier<T> action) {
            documents.add(documentId);
            return action.get();
        }
    }

    private static final class RecordingWriter implements SearchProjectionWriter {
        private final List<UUID> documents = new ArrayList<>();
        private UUID failDocument;
        private RuntimeException failure;

        @Override
        public void apply(String targetIndex, RetrievalProjectionSnapshot snapshot) {
            documents.add(snapshot.documentId());
            if (snapshot.documentId().equals(failDocument)) {
                throw new IllegalStateException("OPENSEARCH_REQUEST_FAILED");
            }
            if (failure != null) throw failure;
        }
    }

    private static final class FakeRepository implements ProjectionEventRepository {
        private final List<ProjectionLease> leases;
        private final List<UUID> checkpoints = new ArrayList<>();
        private final List<UUID> completed = new ArrayList<>();
        private final List<UUID> retried = new ArrayList<>();
        private final List<UUID> failed = new ArrayList<>();
        private Instant claimedLeaseUntil;
        private Instant retryAt;
        private Instant checkpointLeaseUntil;
        private String retryError;
        private String failedError;
        private RuntimeException completeFailure;
        private int renewCalls;
        private int failRenewAt = Integer.MAX_VALUE;
        private int nextLease;
        private final List<Integer> claimLimits = new ArrayList<>();

        private FakeRepository(ProjectionLease... leases) {
            this.leases = List.of(leases);
        }

        @Override
        public List<ProjectionLease> claimBatch(String workerId, int limit, Instant now, Instant leaseUntil) {
            claimedLeaseUntil = leaseUntil;
            claimLimits.add(limit);
            if (nextLease >= leases.size()) return List.of();
            return List.of(leases.get(nextLease++));
        }

        @Override
        public void renew(UUID eventId, UUID token, Instant at, Instant leaseUntil) {
            renewCalls++;
            if (renewCalls == failRenewAt) throw new ProjectionLeaseConflictException();
        }

        @Override
        public void checkpoint(
                UUID eventId, UUID token, UUID progressDocumentId, Instant at, Instant leaseUntil) {
            checkpoints.add(progressDocumentId);
            checkpointLeaseUntil = leaseUntil;
        }

        @Override
        public void complete(UUID eventId, UUID token, Instant at) {
            if (completeFailure != null) throw completeFailure;
            completed.add(eventId);
        }

        @Override
        public void retry(UUID eventId, UUID token, String errorCode, Instant nextAttemptAt, Instant at) {
            retried.add(eventId);
            retryError = errorCode;
            retryAt = nextAttemptAt;
        }

        @Override
        public void fail(UUID eventId, UUID token, String errorCode, Instant at) {
            failed.add(eventId);
            failedError = errorCode;
        }

        @Override public long enqueueDocument(UUID s, UUID d, UUID v, long r, Instant at) { throw new UnsupportedOperationException(); }
        @Override public long enqueueSpace(UUID s, Instant at) { throw new UnsupportedOperationException(); }
        @Override public long currentSequence() { return 0; }
        @Override public List<ProjectionEvent> eventsAfter(long sequence, int limit) { return List.of(); }
    }
}
