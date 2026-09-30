package com.yunjihai.rag.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchClientFactory;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchConnection;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchIndexManager;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchProjectionWriter;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchProperties;
import com.yunjihai.rag.infrastructure.postgres.JdbcProjectionDocumentLock;
import com.yunjihai.rag.infrastructure.postgres.JdbcProjectionEventRepository;
import com.yunjihai.rag.infrastructure.postgres.JdbcRetrievalProjectionSource;
import com.yunjihai.rag.retrieval.projection.ProjectionDocumentLock;
import com.yunjihai.rag.retrieval.projection.ProjectionWorker;
import com.yunjihai.rag.retrieval.projection.ProjectionWorkerPolicy;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectionWorkerIntegrationTest {
    private static final Instant START = Instant.parse("2026-09-30T08:00:00Z");
    private static final String INDEX = "chunks-worker-race";
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_projection_worker_test")
            .withUsername("migration_owner")
            .withPassword("migration_test_only");
    private static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            DockerImageName.parse("opensearchproject/opensearch:3.8.0"))
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private static JdbcClient jdbc;
    private static JdbcProjectionEventRepository events;
    private static JdbcRetrievalProjectionSource source;
    private static ProjectionDocumentLock lock;
    private static OpenSearchConnection search;
    private static OpenSearchProjectionWriter writer;
    private static URI endpoint;
    private static UUID actorId;
    private static UUID spaceId;
    private static UUID documentId;
    private static UUID versionId;

    @BeforeAll
    static void startDependencies() throws Exception {
        POSTGRES.start();
        OPENSEARCH.start();
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_projection_worker_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure().dataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();

        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        events = new JdbcProjectionEventRepository(jdbc);
        source = new JdbcRetrievalProjectionSource(jdbc);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var databaseLock = new JdbcProjectionDocumentLock(jdbc);
        lock = new ProjectionDocumentLock() {
            @Override
            public <T> T withDocumentLock(UUID targetDocument, Supplier<T> action) {
                return transaction.execute(status ->
                        databaseLock.withDocumentLock(targetDocument, action));
            }
        };

        endpoint = URI.create("http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
        search = new OpenSearchClientFactory().create(new OpenSearchProperties(endpoint, null, null));
        new OpenSearchIndexManager(search.client()).createGeneration(INDEX, OptionalInt.empty());
        writer = new OpenSearchProjectionWriter(search.client());
        seedPublishedDocument();
    }

    @AfterAll
    static void stopDependencies() throws Exception {
        if (search != null) search.close();
        OPENSEARCH.stop();
        POSTGRES.stop();
    }

    @Test
    void expiredOlderEventCannotOverwriteNewerProjectionOfCurrentPostgresState() throws Exception {
        long oldSequence = events.enqueueDocument(spaceId, documentId, versionId, 1, START);
        var heldOldLease = events.claimBatch("held-old", 1, START, START.plusSeconds(30));
        assertEquals(oldSequence, heldOldLease.getFirst().sequence());

        jdbc.sql("UPDATE canonical_node SET body_text = '新权威正文' WHERE version_id = :version")
                .param("version", versionId).update();
        long newSequence = events.enqueueDocument(
                spaceId, documentId, versionId, 2, START.plusSeconds(1));

        var policy = new ProjectionWorkerPolicy(
                10, 10, Duration.ofMinutes(1), 3,
                Duration.ofSeconds(1), Duration.ofSeconds(10));
        var newWorker = new ProjectionWorker(events, source, lock, writer, policy,
                Clock.fixed(START.plusSeconds(2), ZoneOffset.UTC));
        assertEquals(1, newWorker.runOnce("new-worker", INDEX));

        var recoveredOldWorker = new ProjectionWorker(events, source, lock, writer, policy,
                Clock.fixed(START.plusSeconds(31), ZoneOffset.UTC));
        assertEquals(1, recoveredOldWorker.runOnce("recovered-old-worker", INDEX));
        new OpenSearchIndexManager(search.client()).refresh(INDEX);

        var stored = indexedNode();
        assertEquals(newSequence, stored.path("projectionSequence").asLong());
        assertEquals("新权威正文", stored.path("bodyText").asText());
    }

    private static void seedPublishedDocument() {
        actorId = UUID.randomUUID();
        spaceId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :external, 'Actor')")
                .param("id", actorId).param("external", actorId.toString()).update();
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by) VALUES (:id, 'Space', :actor)")
                .param("id", spaceId).param("actor", actorId).update();
        jdbc.sql("""
                INSERT INTO document (id, space_id, title, current_published_version_id, revision)
                VALUES (:id, :space, '竞态文档', NULL, 2)
                """).param("id", documentId).param("space", spaceId).update();
        jdbc.sql("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_blob_id, status, parse_status,
                     created_by, status_changed_at)
                VALUES (:id, :document, 1, 'blob', 'PUBLISHED', 'SUCCEEDED', :actor, :at)
                """).param("id", versionId).param("document", documentId).param("actor", actorId)
                .param("at", OffsetDateTime.ofInstant(START, ZoneOffset.UTC)).update();
        jdbc.sql("UPDATE document SET current_published_version_id = :version WHERE id = :document")
                .param("version", versionId).param("document", documentId).update();
        jdbc.sql("""
                INSERT INTO canonical_document
                    (version_id, source_filename, media_type, page_count, parsed_at)
                VALUES (:version, 'source.md', 'text/markdown', 1, :at)
                """).param("version", versionId)
                .param("at", OffsetDateTime.ofInstant(START, ZoneOffset.UTC)).update();
        jdbc.sql("""
                INSERT INTO canonical_node
                    (version_id, node_id, kind, body_text, page, confidence, stable_order)
                VALUES (:version, 'node-1', 'PARAGRAPH', '旧正文', 1, 0.9, 0)
                """).param("version", versionId).update();
    }

    private static com.fasterxml.jackson.databind.JsonNode indexedNode() throws Exception {
        String id = (versionId + ":node-1").replace(":", "%3A");
        var request = HttpRequest.newBuilder(endpoint.resolve("/" + INDEX + "/_doc/" + id)).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return new ObjectMapper().readTree(response.body()).path("_source");
    }
}
