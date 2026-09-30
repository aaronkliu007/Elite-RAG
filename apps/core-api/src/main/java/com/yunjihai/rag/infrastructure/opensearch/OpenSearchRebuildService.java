package com.yunjihai.rag.infrastructure.opensearch;

import com.yunjihai.rag.retrieval.projection.ProjectionCutoverLock;
import com.yunjihai.rag.retrieval.projection.ProjectionEvent;
import com.yunjihai.rag.retrieval.projection.ProjectionEventRepository;
import com.yunjihai.rag.retrieval.projection.ProjectionScope;
import com.yunjihai.rag.retrieval.projection.RetrievalProjectionSnapshot;
import com.yunjihai.rag.retrieval.projection.RetrievalProjectionSource;
import com.yunjihai.rag.retrieval.projection.SearchProjectionWriter;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

public final class OpenSearchRebuildService {
    private final RetrievalProjectionSource source;
    private final ProjectionEventRepository events;
    private final SearchProjectionWriter writer;
    private final OpenSearchIndexManager indexes;
    private final ProjectionCutoverLock cutover;
    private final int scanPageSize;
    private final int replayBatchSize;
    private final OptionalInt vectorDimension;

    public OpenSearchRebuildService(
            RetrievalProjectionSource source,
            ProjectionEventRepository events,
            SearchProjectionWriter writer,
            OpenSearchIndexManager indexes,
            ProjectionCutoverLock cutover,
            int scanPageSize,
            int replayBatchSize,
            OptionalInt vectorDimension) {
        this.source = Objects.requireNonNull(source);
        this.events = Objects.requireNonNull(events);
        this.writer = Objects.requireNonNull(writer);
        this.indexes = Objects.requireNonNull(indexes);
        this.cutover = Objects.requireNonNull(cutover);
        this.vectorDimension = Objects.requireNonNull(vectorDimension);
        if (scanPageSize < 1 || scanPageSize > 1_000
                || replayBatchSize < 1 || replayBatchSize > 10_000) {
            throw new IllegalArgumentException("invalid rebuild batch size");
        }
        this.scanPageSize = scanPageSize;
        this.replayBatchSize = replayBatchSize;
    }

    public RebuildResult rebuild(String generation, Duration cutoverTimeout) {
        OpenSearchIndexManager.validateIndex(generation);
        Objects.requireNonNull(cutoverTimeout);
        indexes.createGeneration(generation, vectorDimension);
        long startSequence = events.currentSequence();
        ValidationState validation = new ValidationState();
        scanAuthority(generation, startSequence, validation);

        long[] appliedSequence = {catchUpUntilStable(generation, startSequence, validation)};
        validateFull(generation, validation);
        long deadlineNanos = deadlineAfter(cutoverTimeout);

        return cutover.withExclusive(remaining(deadlineNanos), () -> {
            ensureBefore(deadlineNanos);
            long finalSequence = events.currentSequence();
            ValidationDelta delta = new ValidationDelta();
            appliedSequence[0] = replayThrough(
                    generation, appliedSequence[0], finalSequence, validation, delta, deadlineNanos);
            if (appliedSequence[0] != finalSequence) throw new RebuildValidationException();
            ensureBefore(deadlineNanos);
            validateDelta(generation, validation, delta, deadlineNanos);
            ensureBefore(deadlineNanos);

            String previousRead = indexes.aliasTarget(OpenSearchIndexManager.READ_ALIAS).orElse(null);
            String previousWrite = indexes.aliasTarget(OpenSearchIndexManager.WRITE_ALIAS).orElse(null);
            if (!Objects.equals(previousRead, previousWrite)) throw new RebuildValidationException();
            ensureBefore(deadlineNanos);
            indexes.switchAliases(previousRead, generation);
            Counts counts = validation.counts();
            return new RebuildResult(
                    generation, previousRead, startSequence, finalSequence,
                    counts.documents(), counts.nodes());
        });
    }

    private void scanAuthority(
            String generation, long projectionSequence, ValidationState validation) {
        UUID cursor = null;
        while (true) {
            var page = source.pageAll(cursor, scanPageSize, projectionSequence);
            if (page.isEmpty()) return;
            for (RetrievalProjectionSnapshot snapshot : page) {
                writer.apply(generation, snapshot);
                validation.record(snapshot, null);
            }
            UUID next = page.getLast().documentId();
            if (cursor != null && next.compareTo(cursor) <= 0) throw new RebuildValidationException();
            cursor = next;
        }
    }

    private long catchUpUntilStable(
            String generation, long applied, ValidationState validation) {
        long cursor = applied;
        while (true) {
            long target = events.currentSequence();
            cursor = replayThrough(generation, cursor, target, validation, null, 0);
            if (cursor == target && events.currentSequence() == target) return cursor;
        }
    }

    private long replayThrough(
            String generation, long applied, long target,
            ValidationState validation, ValidationDelta delta, long deadlineNanos) {
        long cursor = applied;
        while (cursor < target) {
            ensureBefore(deadlineNanos);
            var batch = events.eventsAfter(cursor, replayBatchSize).stream()
                    .filter(event -> event.sequence() <= target).toList();
            ensureBefore(deadlineNanos);
            if (batch.isEmpty()) throw new RebuildValidationException();
            for (ProjectionEvent event : batch) {
                ensureBefore(deadlineNanos);
                replayEvent(generation, event, validation, delta, deadlineNanos);
                cursor = event.sequence();
            }
        }
        return cursor;
    }

    private void replayEvent(
            String generation, ProjectionEvent event,
            ValidationState validation, ValidationDelta delta, long deadlineNanos) {
        ensureBefore(deadlineNanos);
        if (event.scope() == ProjectionScope.DOCUMENT) {
            var snapshot = source.load(event.spaceId(), event.documentId(), event.sequence());
            ensureBefore(deadlineNanos);
            writer.apply(generation, snapshot);
            validation.record(snapshot, delta);
            return;
        }
        if (delta != null) delta.spaces().add(event.spaceId());
        UUID cursor = null;
        while (true) {
            ensureBefore(deadlineNanos);
            var page = source.pageSpace(
                    event.spaceId(), cursor, scanPageSize, event.sequence());
            ensureBefore(deadlineNanos);
            if (page.isEmpty()) return;
            for (RetrievalProjectionSnapshot snapshot : page) {
                ensureBefore(deadlineNanos);
                writer.apply(generation, snapshot);
                validation.record(snapshot, delta);
            }
            UUID next = page.getLast().documentId();
            if (cursor != null && next.compareTo(cursor) <= 0) throw new RebuildValidationException();
            cursor = next;
        }
    }

    private void validateFull(String generation, ValidationState validation) {
        validateGlobal(generation, validation, 0);
        for (ProjectionExpectation expectation : validation.expectations().values()) {
            validateDocument(generation, expectation);
        }
        for (var space : validation.nodesBySpace().entrySet()) {
            validateSpace(generation, space.getKey(), space.getValue());
        }
    }

    private void validateDelta(
            String generation, ValidationState validation, ValidationDelta delta,
            long deadlineNanos) {
        validateGlobal(generation, validation, deadlineNanos);
        for (UUID documentId : delta.documents()) {
            ensureBefore(deadlineNanos);
            ProjectionExpectation expectation = validation.expectations().get(documentId);
            if (expectation == null) throw new RebuildValidationException();
            validateDocument(generation, expectation);
        }
        for (UUID spaceId : delta.spaces()) {
            ensureBefore(deadlineNanos);
            validateSpace(generation, spaceId,
                    validation.nodesBySpace().getOrDefault(spaceId, 0L));
        }
    }

    private void validateGlobal(
            String generation, ValidationState validation, long deadlineNanos) {
        ensureBefore(deadlineNanos);
        indexes.refresh(generation);
        ensureBefore(deadlineNanos);
        Counts expected = validation.counts();
        long nodes = indexes.countNodes(generation);
        ensureBefore(deadlineNanos);
        long tombstones = indexes.countTombstones(generation);
        if (nodes != expected.nodes()
                || tombstones != validation.expectations().size()) {
            throw new RebuildValidationException();
        }
    }

    private void validateDocument(String generation, ProjectionExpectation expectation) {
        if (indexes.countNodesForDocumentVersion(
                generation, expectation.documentId(), expectation.versionId())
                != expectation.nodeCount()) {
            throw new RebuildValidationException();
        }
        if (expectation.sampleNodeId() != null
                && indexes.countExpectedNode(generation, expectation.sampleNodeId(),
                expectation.spaceId(), expectation.documentId(), expectation.versionId()) != 1) {
            throw new RebuildValidationException();
        }
    }

    private void validateSpace(String generation, UUID spaceId, long expectedNodes) {
        if (indexes.countNodesForSpace(generation, spaceId) != expectedNodes) {
            throw new RebuildValidationException();
        }
    }

    private static long deadlineAfter(Duration timeout) {
        long now = System.nanoTime();
        try {
            return Math.addExact(now, timeout.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static Duration remaining(long deadlineNanos) {
        long now = System.nanoTime();
        if (now - deadlineNanos >= 0) throw new RebuildValidationException();
        return Duration.ofNanos(deadlineNanos - now);
    }

    private static void ensureBefore(long deadlineNanos) {
        if (deadlineNanos != 0 && System.nanoTime() - deadlineNanos >= 0) {
            throw new RebuildValidationException();
        }
    }

    private record ProjectionExpectation(
            UUID spaceId, UUID documentId, UUID versionId, int nodeCount, String sampleNodeId) { }

    private static final class ValidationState {
        private final Map<UUID, ProjectionExpectation> expectations = new HashMap<>();
        private final Map<UUID, Long> nodesBySpace = new HashMap<>();
        private long indexableDocuments;
        private long nodes;

        private void record(RetrievalProjectionSnapshot snapshot, ValidationDelta delta) {
            ProjectionExpectation next = new ProjectionExpectation(
                    snapshot.spaceId(), snapshot.documentId(), snapshot.versionId(),
                    snapshot.indexable() ? snapshot.nodes().size() : 0,
                    snapshot.indexable() ? snapshot.nodes().getFirst().indexDocumentId() : null);
            ProjectionExpectation previous = expectations.put(snapshot.documentId(), next);
            if (previous != null) {
                nodes -= previous.nodeCount();
                if (previous.nodeCount() > 0) indexableDocuments--;
                mergeSpace(previous.spaceId(), -previous.nodeCount());
                if (delta != null) delta.spaces().add(previous.spaceId());
            }
            nodes += next.nodeCount();
            if (next.nodeCount() > 0) indexableDocuments++;
            mergeSpace(next.spaceId(), next.nodeCount());
            if (delta != null) {
                delta.documents().add(next.documentId());
                delta.spaces().add(next.spaceId());
            }
        }

        private void mergeSpace(UUID spaceId, long nodeDelta) {
            nodesBySpace.compute(spaceId, (ignored, current) -> {
                long next = (current == null ? 0 : current) + nodeDelta;
                return next == 0 ? null : next;
            });
        }

        private Map<UUID, ProjectionExpectation> expectations() {
            return expectations;
        }

        private Map<UUID, Long> nodesBySpace() {
            return nodesBySpace;
        }

        private Counts counts() {
            return new Counts(indexableDocuments, nodes);
        }
    }

    private record ValidationDelta(Set<UUID> documents, Set<UUID> spaces) {
        private ValidationDelta() {
            this(new HashSet<>(), new HashSet<>());
        }
    }

    private record Counts(long documents, long nodes) { }
}
