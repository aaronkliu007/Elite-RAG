package com.yunjihai.rag.infrastructure.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.projection.ProjectionCutoverLock;
import com.yunjihai.rag.retrieval.projection.ProjectionEvent;
import com.yunjihai.rag.retrieval.projection.ProjectionEventRepository;
import com.yunjihai.rag.retrieval.projection.ProjectionEventStatus;
import com.yunjihai.rag.retrieval.projection.ProjectionLease;
import com.yunjihai.rag.retrieval.projection.ProjectionNode;
import com.yunjihai.rag.retrieval.projection.ProjectionScope;
import com.yunjihai.rag.retrieval.projection.RetrievalProjectionSnapshot;
import com.yunjihai.rag.retrieval.projection.RetrievalProjectionSource;
import com.yunjihai.rag.retrieval.projection.SearchProjectionWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenSearchRebuildIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-30T09:00:00Z");
    private static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            DockerImageName.parse("opensearchproject/opensearch:3.8.0"))
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private static OpenSearchConnection connection;
    private static OpenSearchIndexManager indexes;
    private static OpenSearchProjectionWriter writer;
    private static URI endpoint;

    @BeforeAll
    static void startOpenSearch() {
        OPENSEARCH.start();
        endpoint = URI.create("http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
        connection = new OpenSearchClientFactory().create(new OpenSearchProperties(endpoint, null, null));
        indexes = new OpenSearchIndexManager(connection.client());
        writer = new OpenSearchProjectionWriter(connection.client());
    }

    @AfterAll
    static void stopOpenSearch() throws Exception {
        if (connection != null) connection.close();
        OPENSEARCH.stop();
    }

    @Test
    void replaysFinalEventInsideExclusiveLockBeforeAtomicAliasSwitch() throws Exception {
        String oldIndex = initializeOldGeneration("chunks-rebuild-old");
        var source = new MutableSource("扫描时正文");
        var events = new FakeEvents(10);
        var cutover = new HookCutoverLock(() -> {
            source.body = "切换前最后变化";
            events.publishDocument(11, source.documentId, source.versionId);
        });
        var service = service(source, events, writer, cutover);

        RebuildResult result = service.rebuild("chunks-rebuild-new", Duration.ofSeconds(5));

        assertEquals(oldIndex, result.previousIndex());
        assertEquals(10, result.startSequence());
        assertEquals(11, result.finalSequence());
        assertEquals(1, result.indexedDocuments());
        assertEquals(1, result.indexedNodes());
        assertEquals("chunks-rebuild-new", indexes.aliasTarget(OpenSearchIndexManager.READ_ALIAS).orElseThrow());
        assertEquals("chunks-rebuild-new", indexes.aliasTarget(OpenSearchIndexManager.WRITE_ALIAS).orElseThrow());
        assertEquals("切换前最后变化", bodyThroughReadAlias(source.indexDocumentId()));
    }

    @Test
    void validationFailureKeepsExistingAliases() {
        String oldIndex = initializeOldGeneration("chunks-validation-old");
        var source = new MutableSource("正文");
        SearchProjectionWriter droppingWriter = (index, snapshot) -> { };
        var service = service(source, new FakeEvents(20), droppingWriter, new HookCutoverLock(() -> { }));

        assertThrows(RebuildValidationException.class,
                () -> service.rebuild("chunks-validation-bad", Duration.ofSeconds(5)));

        assertEquals(oldIndex, indexes.aliasTarget(OpenSearchIndexManager.READ_ALIAS).orElseThrow());
        assertEquals(oldIndex, indexes.aliasTarget(OpenSearchIndexManager.WRITE_ALIAS).orElseThrow());
    }

    @Test
    void initializesAliasesForEmptyAuthority() {
        String oldIndex = initializeOldGeneration("chunks-empty-old");
        var source = new MutableSource(null);
        source.empty = true;
        var service = service(source, new FakeEvents(0), writer, new HookCutoverLock(() -> { }));

        RebuildResult result = service.rebuild("chunks-empty-new", Duration.ofSeconds(5));

        assertEquals(oldIndex, result.previousIndex());
        assertEquals(0, result.indexedDocuments());
        assertEquals(0, result.indexedNodes());
        assertEquals("chunks-empty-new", indexes.aliasTarget(OpenSearchIndexManager.READ_ALIAS).orElseThrow());
    }

    @Test
    void cutoverTimeoutKeepsExistingAliases() {
        String oldIndex = initializeOldGeneration("chunks-timeout-old");
        var source = new MutableSource("正文");
        ProjectionCutoverLock timeout = new ProjectionCutoverLock() {
            @Override public <T> T withShared(Supplier<T> action) { return action.get(); }
            @Override public <T> T withExclusive(Duration duration, Supplier<T> action) {
                throw new IllegalStateException("PROJECTION_CUTOVER_LOCK_TIMEOUT");
            }
        };
        var service = service(source, new FakeEvents(30), writer, timeout);

        assertThrows(IllegalStateException.class,
                () -> service.rebuild("chunks-timeout-new", Duration.ofMillis(50)));

        assertEquals(oldIndex, indexes.aliasTarget(OpenSearchIndexManager.READ_ALIAS).orElseThrow());
        assertEquals(oldIndex, indexes.aliasTarget(OpenSearchIndexManager.WRITE_ALIAS).orElseThrow());
    }

    private static OpenSearchRebuildService service(
            RetrievalProjectionSource source,
            ProjectionEventRepository events,
            SearchProjectionWriter targetWriter,
            ProjectionCutoverLock cutover) {
        return new OpenSearchRebuildService(
                source, events, targetWriter, indexes, cutover,
                2, 10, OptionalInt.empty());
    }

    private static String initializeOldGeneration(String oldIndex) {
        indexes.createGeneration(oldIndex, OptionalInt.empty());
        String current = indexes.aliasTarget(OpenSearchIndexManager.READ_ALIAS).orElse(null);
        indexes.switchAliases(current, oldIndex);
        return oldIndex;
    }

    private static String bodyThroughReadAlias(String id) throws Exception {
        String encoded = id.replace(":", "%3A");
        var request = HttpRequest.newBuilder(endpoint.resolve("/" + OpenSearchIndexManager.READ_ALIAS
                + "/_doc/" + encoded)).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return new ObjectMapper().readTree(response.body()).path("_source").path("bodyText").asText();
    }

    private static final class MutableSource implements RetrievalProjectionSource {
        private final UUID spaceId = UUID.randomUUID();
        private final UUID documentId = UUID.randomUUID();
        private final UUID versionId = UUID.randomUUID();
        private String body;
        private boolean empty;

        private MutableSource(String body) {
            this.body = body;
        }

        private String indexDocumentId() {
            return versionId + ":node-1";
        }

        private RetrievalProjectionSnapshot snapshot(long sequence) {
            var node = new ProjectionNode(
                    indexDocumentId(), UUID.nameUUIDFromBytes(indexDocumentId().getBytes(StandardCharsets.UTF_8)),
                    "node-1", "PARAGRAPH", body, List.of("章节"), 0,
                    new MarkdownLocator(List.of("章节"), "node-1"), 0.9);
            return new RetrievalProjectionSnapshot(
                    spaceId, documentId, versionId, "重建文档", 2, sequence,
                    NOW, NOW, Set.of(), Set.of(), false,
                    Map.of("industry", "软件"), List.of(node), true);
        }

        @Override
        public RetrievalProjectionSnapshot load(UUID space, UUID document, long sequence) {
            return snapshot(sequence);
        }

        @Override
        public List<RetrievalProjectionSnapshot> pageSpace(
                UUID space, UUID cursor, int limit, long sequence) {
            if (empty || cursor != null) return List.of();
            return List.of(snapshot(sequence));
        }

        @Override
        public List<RetrievalProjectionSnapshot> pageAll(UUID cursor, int limit, long sequence) {
            if (empty || cursor != null) return List.of();
            return List.of(snapshot(sequence));
        }
    }

    private static final class FakeEvents implements ProjectionEventRepository {
        private long sequence;
        private final List<ProjectionEvent> published = new ArrayList<>();

        private FakeEvents(long sequence) {
            this.sequence = sequence;
        }

        private void publishDocument(long next, UUID document, UUID version) {
            sequence = next;
            published.add(new ProjectionEvent(
                    UUID.randomUUID(), next, ProjectionScope.DOCUMENT,
                    UUID.randomUUID(), document, version, 2L, null,
                    ProjectionEventStatus.PENDING, 0, NOW, null, NOW, NOW));
        }

        @Override public long currentSequence() { return sequence; }
        @Override public List<ProjectionEvent> eventsAfter(long after, int limit) {
            return published.stream().filter(event -> event.sequence() > after).limit(limit).toList();
        }
        @Override public long enqueueDocument(UUID s, UUID d, UUID v, long r, Instant at) { throw new UnsupportedOperationException(); }
        @Override public long enqueueSpace(UUID s, Instant at) { throw new UnsupportedOperationException(); }
        @Override public List<ProjectionLease> claimBatch(String w, int l, Instant n, Instant u) { return List.of(); }
        @Override public void checkpoint(UUID e, UUID t, UUID p, Instant at) { throw new UnsupportedOperationException(); }
        @Override public void complete(UUID e, UUID t, Instant at) { throw new UnsupportedOperationException(); }
        @Override public void retry(UUID e, UUID t, String c, Instant n, Instant at) { throw new UnsupportedOperationException(); }
        @Override public void fail(UUID e, UUID t, String c, Instant at) { throw new UnsupportedOperationException(); }
    }

    private static final class HookCutoverLock implements ProjectionCutoverLock {
        private final Runnable beforeExclusive;

        private HookCutoverLock(Runnable beforeExclusive) {
            this.beforeExclusive = beforeExclusive;
        }

        @Override public <T> T withShared(Supplier<T> action) { return action.get(); }
        @Override public <T> T withExclusive(Duration timeout, Supplier<T> action) {
            beforeExclusive.run();
            return action.get();
        }
    }
}
