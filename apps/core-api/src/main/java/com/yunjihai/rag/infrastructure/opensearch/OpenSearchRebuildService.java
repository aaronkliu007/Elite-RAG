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
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
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
        Map<UUID, Integer> nodeCounts = new HashMap<>();
        scanAuthority(generation, startSequence, nodeCounts);

        long[] appliedSequence = {catchUpUntilStable(generation, startSequence, nodeCounts)};
        validate(generation, nodeCounts);

        return cutover.withExclusive(cutoverTimeout, () -> {
            long finalSequence = events.currentSequence();
            appliedSequence[0] = replayThrough(
                    generation, appliedSequence[0], finalSequence, nodeCounts);
            if (appliedSequence[0] != finalSequence) throw new RebuildValidationException();
            validate(generation, nodeCounts);

            String previousRead = indexes.aliasTarget(OpenSearchIndexManager.READ_ALIAS).orElse(null);
            String previousWrite = indexes.aliasTarget(OpenSearchIndexManager.WRITE_ALIAS).orElse(null);
            if (!Objects.equals(previousRead, previousWrite)) throw new RebuildValidationException();
            indexes.switchAliases(previousRead, generation);
            Counts counts = counts(nodeCounts);
            return new RebuildResult(
                    generation, previousRead, startSequence, finalSequence,
                    counts.documents(), counts.nodes());
        });
    }

    private void scanAuthority(
            String generation, long projectionSequence, Map<UUID, Integer> nodeCounts) {
        UUID cursor = null;
        while (true) {
            var page = source.pageAll(cursor, scanPageSize, projectionSequence);
            if (page.isEmpty()) return;
            for (RetrievalProjectionSnapshot snapshot : page) {
                writer.apply(generation, snapshot);
                recordCount(nodeCounts, snapshot);
            }
            UUID next = page.getLast().documentId();
            if (cursor != null && next.compareTo(cursor) <= 0) throw new RebuildValidationException();
            cursor = next;
        }
    }

    private long catchUpUntilStable(
            String generation, long applied, Map<UUID, Integer> nodeCounts) {
        long cursor = applied;
        while (true) {
            long target = events.currentSequence();
            cursor = replayThrough(generation, cursor, target, nodeCounts);
            if (cursor == target && events.currentSequence() == target) return cursor;
        }
    }

    private long replayThrough(
            String generation, long applied, long target, Map<UUID, Integer> nodeCounts) {
        long cursor = applied;
        while (cursor < target) {
            var batch = events.eventsAfter(cursor, replayBatchSize).stream()
                    .filter(event -> event.sequence() <= target).toList();
            if (batch.isEmpty()) throw new RebuildValidationException();
            for (ProjectionEvent event : batch) {
                replayEvent(generation, event, nodeCounts);
                cursor = event.sequence();
            }
        }
        return cursor;
    }

    private void replayEvent(
            String generation, ProjectionEvent event, Map<UUID, Integer> nodeCounts) {
        if (event.scope() == ProjectionScope.DOCUMENT) {
            var snapshot = source.load(event.spaceId(), event.documentId(), event.sequence());
            writer.apply(generation, snapshot);
            recordCount(nodeCounts, snapshot);
            return;
        }
        UUID cursor = null;
        while (true) {
            var page = source.pageSpace(
                    event.spaceId(), cursor, scanPageSize, event.sequence());
            if (page.isEmpty()) return;
            for (RetrievalProjectionSnapshot snapshot : page) {
                writer.apply(generation, snapshot);
                recordCount(nodeCounts, snapshot);
            }
            UUID next = page.getLast().documentId();
            if (cursor != null && next.compareTo(cursor) <= 0) throw new RebuildValidationException();
            cursor = next;
        }
    }

    private void validate(String generation, Map<UUID, Integer> nodeCounts) {
        indexes.refresh(generation);
        if (indexes.countNodes(generation) != counts(nodeCounts).nodes()) {
            throw new RebuildValidationException();
        }
    }

    private static void recordCount(
            Map<UUID, Integer> nodeCounts, RetrievalProjectionSnapshot snapshot) {
        nodeCounts.put(snapshot.documentId(), snapshot.indexable() ? snapshot.nodes().size() : 0);
    }

    private static Counts counts(Map<UUID, Integer> nodeCounts) {
        long documents = nodeCounts.values().stream().filter(count -> count > 0).count();
        long nodes = nodeCounts.values().stream().mapToLong(Integer::longValue).sum();
        return new Counts(documents, nodes);
    }

    private record Counts(long documents, long nodes) { }
}
