package com.yunjihai.rag.infrastructure.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.projection.ProjectionAclGrant;
import com.yunjihai.rag.retrieval.projection.ProjectionNode;
import com.yunjihai.rag.retrieval.projection.RetrievalProjectionSnapshot;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenSearchProjectionWriterIntegrationTest {
    private static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            DockerImageName.parse("opensearchproject/opensearch:3.8.0"))
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static URI endpoint;
    private static OpenSearchConnection connection;
    private static OpenSearchIndexManager indexes;
    private static OpenSearchProjectionWriter writer;

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
    void createsCjkNestedAclAndOptionalLuceneVectorMappings() throws Exception {
        indexes.createGeneration("chunks-mapping", OptionalInt.empty());
        indexes.createGeneration("chunks-vector", OptionalInt.of(4));

        String baseMapping = request("GET", "/chunks-mapping/_mapping", null).body();
        String vectorMapping = request("GET", "/chunks-vector/_mapping", null).body();

        assertTrue(baseMapping.contains("\"analyzer\":\"cjk\""));
        assertTrue(baseMapping.contains("\"spaceAcl\":{\"type\":\"nested\""));
        assertTrue(baseMapping.contains("\"documentAcl\":{\"type\":\"nested\""));
        assertTrue(vectorMapping.contains("\"type\":\"knn_vector\""));
        assertTrue(vectorMapping.contains("\"dimension\":4"));
        assertTrue(vectorMapping.contains("\"engine\":\"lucene\""));
    }

    @Test
    void writesStableIdsIdempotentlyRejectsStaleSequencesAndRemovesResidualNodes() throws Exception {
        String index = "chunks-idempotent";
        indexes.createGeneration(index, OptionalInt.empty());
        var first = snapshot(10, List.of(node("node-1", "第一段"), node("node-2", "第二段")));

        writer.apply(index, first);
        writer.apply(index, first);
        indexes.refresh(index);

        assertEquals(2, indexes.countNodes(index));
        JsonNode stored = source(index, "version-1:node-1");
        assertEquals("NODE", stored.get("recordType").asText());
        assertEquals(10, stored.get("projectionSequence").asLong());
        assertEquals("第一段", stored.get("bodyText").asText());

        writer.apply(index, snapshot(9, List.of(node("node-1", "旧内容"))));
        indexes.refresh(index);
        assertEquals("第一段", source(index, "version-1:node-1").get("bodyText").asText());
        assertEquals(2, indexes.countNodes(index));

        writer.apply(index, snapshot(11, List.of(node("node-1", "新内容"))));
        indexes.refresh(index);
        assertEquals(1, indexes.countNodes(index));
        assertEquals("新内容", source(index, "version-1:node-1").get("bodyText").asText());
        assertEquals(404, request("GET", "/" + index + "/_doc/version-1%3Anode-2", null).statusCode());
        assertEquals("TOMBSTONE", source(index, tombstoneId()).get("recordType").asText());
    }

    @Test
    void deletionSnapshotClearsNodesAndKeepsNewestTombstone() throws Exception {
        String index = "chunks-delete";
        indexes.createGeneration(index, OptionalInt.empty());
        writer.apply(index, snapshot(20, List.of(node("node-1", "正文"))));

        writer.apply(index, RetrievalProjectionSnapshot.deletion(spaceId(), documentId(), 8, 21));
        indexes.refresh(index);

        assertEquals(0, indexes.countNodes(index));
        assertEquals(21, source(index, tombstoneId()).get("projectionSequence").asLong());
    }

    @Test
    void doesNotReportSuccessWhenAnyBulkItemFails() throws Exception {
        String index = "chunks-broken";
        String brokenMapping = """
                {"mappings":{"properties":{"bodyText":{"type":"integer"}}}}
                """;
        assertTrue(request("PUT", "/" + index, brokenMapping).statusCode() < 300);

        assertThrows(IllegalStateException.class,
                () -> writer.apply(index, snapshot(30, List.of(node("node-1", "不是整数")))));
    }

    private static RetrievalProjectionSnapshot snapshot(long sequence, List<ProjectionNode> nodes) {
        UUID principal = UUID.fromString("00000000-0000-0000-0000-000000000003");
        return new RetrievalProjectionSnapshot(
                spaceId(), documentId(), versionId(), "知识文档", 8, sequence,
                Instant.parse("2026-09-30T08:00:00Z"),
                Instant.parse("2026-09-30T07:59:00Z"),
                Set.of(new ProjectionAclGrant("USER:" + principal, null)),
                Set.of(new ProjectionAclGrant("ROLE:" + principal,
                        Instant.parse("2027-01-01T00:00:00Z"))),
                true,
                Map.of("industry", "软件", "year", "2026"),
                nodes,
                true);
    }

    private static ProjectionNode node(String nodeId, String text) {
        String indexId = "version-1:" + nodeId;
        return new ProjectionNode(
                indexId,
                UUID.nameUUIDFromBytes(indexId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                nodeId,
                "PARAGRAPH",
                text,
                List.of("第一章"),
                nodeId.equals("node-1") ? 0 : 1,
                new MarkdownLocator(List.of("第一章"), nodeId),
                0.95);
    }

    private static JsonNode source(String index, String id) throws Exception {
        var response = request("GET", "/" + index + "/_doc/" + id.replace(":", "%3A"), null);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body()).get("_source");
    }

    private static HttpResponse<String> request(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(endpoint.resolve(path)).header("Content-Type", "application/json");
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static UUID spaceId() {
        return UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    private static UUID documentId() {
        return UUID.fromString("00000000-0000-0000-0000-000000000002");
    }

    private static UUID versionId() {
        return UUID.nameUUIDFromBytes("version-1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String tombstoneId() {
        return documentId() + ":tombstone";
    }
}
