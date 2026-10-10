package com.yunjihai.rag.infrastructure.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yunjihai.rag.qa.sanitization.RegexSensitiveContentSanitizer;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.embedding.EmbeddingPort;
import com.yunjihai.rag.retrieval.embedding.EmbeddingVector;
import com.yunjihai.rag.retrieval.projection.ProjectionNode;
import com.yunjihai.rag.retrieval.projection.RetrievalProjectionSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenSearchProjectionWriterTest {

    @Test
    void embedsOnlySanitizedCanonicalNodeBodiesAndWritesMatchingVectors() throws Exception {
        try (var search = new FakeOpenSearch()) {
            var embeddedTexts = new ArrayList<List<String>>();
            EmbeddingPort embeddings = texts -> {
                embeddedTexts.add(List.copyOf(texts));
                return List.of(
                        new EmbeddingVector(List.of(0.1, 0.2, 0.3)),
                        new EmbeddingVector(List.of(0.4, 0.5, 0.6)));
            };
            var writer = enabledWriter(search, embeddings, 3);

            writer.apply("chunks-vector", snapshot(List.of(
                    node("node-1", "联系 13800138000"),
                    node("node-2", "普通正文"))));

            assertThat(embeddedTexts).containsExactly(List.of("联系 [PHONE]", "普通正文"));
            assertThat(search.bulkBody())
                    .doesNotContain("13800138000")
                    .contains("联系 [PHONE]")
                    .contains("\"embedding\":[0.1,0.2,0.3]")
                    .contains("\"embedding\":[0.4,0.5,0.6]");
        }
    }

    @Test
    void blockedNodeFailsBeforeAnyOpenSearchWrite() throws Exception {
        try (var search = new FakeOpenSearch()) {
            var writer = enabledWriter(search, texts -> List.of(
                    new EmbeddingVector(List.of(0.1, 0.2, 0.3))), 3);
            var blocked = "-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----";

            assertThatThrownBy(() -> writer.apply(
                    "chunks-vector", snapshot(List.of(node("node-1", blocked)))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("PROJECTION_CONTENT_BLOCKED");

            assertThat(search.requestCount()).isZero();
        }
    }

    @Test
    void mismatchedBatchSizeFailsBeforeAnyOpenSearchWrite() throws Exception {
        try (var search = new FakeOpenSearch()) {
            var writer = enabledWriter(search, texts -> List.of(
                    new EmbeddingVector(List.of(0.1, 0.2, 0.3))), 3);

            assertThatThrownBy(() -> writer.apply("chunks-vector", snapshot(List.of(
                    node("node-1", "第一段"), node("node-2", "第二段")))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("PROJECTION_EMBEDDING_BATCH_INVALID");

            assertThat(search.requestCount()).isZero();
        }
    }

    @Test
    void mismatchedVectorDimensionFailsBeforeAnyOpenSearchWrite() throws Exception {
        try (var search = new FakeOpenSearch()) {
            var writer = enabledWriter(search, texts -> List.of(
                    new EmbeddingVector(List.of(0.1, 0.2))), 3);

            assertThatThrownBy(() -> writer.apply(
                    "chunks-vector", snapshot(List.of(node("node-1", "正文")))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("PROJECTION_EMBEDDING_DIMENSION_INVALID");

            assertThat(search.requestCount()).isZero();
        }
    }

    @Test
    void disabledVectorProjectionDoesNotCallModelOrWriteEmbeddingField() throws Exception {
        try (var search = new FakeOpenSearch()) {
            var calls = new AtomicInteger();
            EmbeddingPort embeddings = texts -> {
                calls.incrementAndGet();
                throw new AssertionError("disabled projection must not call embedding model");
            };
            var writer = new OpenSearchProjectionWriter(
                    search.client(), new ObjectMapper(), ignored -> { },
                    new RegexSensitiveContentSanitizer(Set.of()), embeddings, false, 0);

            writer.apply("chunks-lexical", snapshot(List.of(node("node-1", "普通正文"))));

            assertThat(calls).hasValue(0);
            assertThat(search.bulkBody()).doesNotContain("\"embedding\"");
        }
    }

    private static OpenSearchProjectionWriter enabledWriter(
            FakeOpenSearch search, EmbeddingPort embeddings, int dimension) {
        return new OpenSearchProjectionWriter(
                search.client(), new ObjectMapper(), ignored -> { },
                new RegexSensitiveContentSanitizer(Set.of()), embeddings, true, dimension);
    }

    private static RetrievalProjectionSnapshot snapshot(List<ProjectionNode> nodes) {
        return new RetrievalProjectionSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                "知识文档", 1, 2,
                Instant.parse("2026-10-10T08:00:00Z"),
                Instant.parse("2026-10-10T07:59:00Z"),
                Set.of(), Set.of(), false, Map.of(), nodes, true);
    }

    private static ProjectionNode node(String nodeId, String body) {
        String indexId = "version-1:" + nodeId;
        return new ProjectionNode(
                indexId,
                UUID.nameUUIDFromBytes(indexId.getBytes(StandardCharsets.UTF_8)),
                nodeId, "PARAGRAPH", body, List.of("章节"), 0,
                new MarkdownLocator(List.of("章节"), nodeId), 0.9);
    }

    private static final class FakeOpenSearch implements AutoCloseable {
        private final HttpServer server;
        private final OpenSearchConnection connection;
        private final AtomicInteger requests = new AtomicInteger();
        private volatile String bulkBody = "";

        private FakeOpenSearch() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::respond);
            server.start();
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            connection = new OpenSearchClientFactory().create(
                    new OpenSearchProperties(endpoint, null, null));
        }

        private org.opensearch.client.opensearch.OpenSearchClient client() {
            return connection.client();
        }

        private int requestCount() {
            return requests.get();
        }

        private String bulkBody() {
            return bulkBody;
        }

        private void respond(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            String response;
            if ("/_bulk".equals(path)) {
                bulkBody = requestBody;
                int items = (int) requestBody.lines().count() / 2;
                response = "{\"errors\":false,\"items\":["
                        + java.util.stream.IntStream.range(0, items)
                                .mapToObj(ignored -> "{\"update\":{\"status\":200}}")
                                .collect(java.util.stream.Collectors.joining(","))
                        + "]}";
            } else if (path.contains("/_update/")) {
                response = "{\"result\":\"created\"}";
            } else if (path.endsWith("/_delete_by_query")) {
                response = "{\"timed_out\":false,\"version_conflicts\":0,\"failures\":[]}";
            } else if (path.endsWith("/_count")) {
                response = "{\"count\":0}";
            } else {
                response = "{}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() throws Exception {
            connection.close();
            server.stop(0);
        }
    }
}
