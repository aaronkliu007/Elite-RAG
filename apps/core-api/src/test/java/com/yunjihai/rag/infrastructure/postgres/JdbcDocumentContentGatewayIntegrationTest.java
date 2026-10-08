package com.yunjihai.rag.infrastructure.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcDocumentContentGatewayIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_content_gateway_test")
            .withUsername("migration_owner")
            .withPassword("migration_test_only");
    private static final Instant AT = Instant.parse("2026-10-08T10:00:00Z");
    private static final byte[] SOURCE = "source-bytes".getBytes(StandardCharsets.UTF_8);
    private static JdbcClient jdbc;
    private static UUID documentId;
    private static UUID versionId;
    private static String objectKey;

    @BeforeAll
    static void startDatabase() throws Exception {
        POSTGRES.start();
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_content_gateway_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        seedContent();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void rendersCanonicalNodesInStableOrderAsUtf8Preview() {
        var gateway = new JdbcDocumentContentGateway(jdbc, (key, maxBytes) -> SOURCE);

        var preview = gateway.readParsed(documentId, versionId);

        assertThat(preview.mediaType()).isEqualTo("text/plain;charset=UTF-8");
        assertThat(preview.filename()).isEqualTo("source.md.txt");
        assertThat(new String(preview.bytes(), StandardCharsets.UTF_8))
                .isEqualTo("第一段\n\n第二段\n\n第三段");
    }

    @Test
    void readsOnlyCanonicalSourceKeyAndVerifiesStoredLength() {
        var readKey = new AtomicReference<String>();
        var readLimit = new AtomicLong();
        var gateway = new JdbcDocumentContentGateway(jdbc, (key, maxBytes) -> {
            readKey.set(key);
            readLimit.set(maxBytes);
            return SOURCE;
        });

        var source = gateway.readSource(documentId, versionId);

        assertThat(readKey).hasValue(objectKey);
        assertThat(readLimit).hasValue(100L * 1024 * 1024);
        assertThat(source.filename()).isEqualTo("source.md");
        assertThat(source.mediaType()).isEqualTo("text/markdown");
        assertThat(source.bytes()).isEqualTo(SOURCE);

        var wrongLength = new JdbcDocumentContentGateway(
                jdbc, (key, maxBytes) -> "short".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> wrongLength.readSource(documentId, versionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DOCUMENT_SOURCE_LENGTH_MISMATCH")
                .hasNoCause();
    }

    private static void seedContent() {
        var actorId = UUID.randomUUID();
        var spaceId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        var jobId = UUID.randomUUID();
        var sha = "a".repeat(64);
        objectKey = "sha256/aa/" + sha;
        var at = OffsetDateTime.ofInstant(AT, ZoneOffset.UTC);
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :external, '用户')")
                .param("id", actorId).param("external", actorId.toString()).update();
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by) VALUES (:id, '空间', :actor)")
                .param("id", spaceId).param("actor", actorId).update();
        jdbc.sql("""
                INSERT INTO document (id, space_id, title, revision)
                VALUES (:id, :space, '文档', 1)
                """).param("id", documentId).param("space", spaceId).update();
        jdbc.sql("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_blob_id, status, parse_status,
                     created_by, status_changed_by, status_changed_at)
                VALUES (:id, :document, 1, :blob, 'PUBLISHED', 'SUCCEEDED', :actor, :actor, :at)
                """).param("id", versionId).param("document", documentId).param("blob", objectKey)
                .param("actor", actorId).param("at", at).update();
        jdbc.sql("UPDATE document SET current_published_version_id = :version WHERE id = :document")
                .param("version", versionId).param("document", documentId).update();
        jdbc.sql("""
                INSERT INTO ingestion_job
                    (id, document_id, version_id, requested_by, source_blob_id, sha256,
                     source_filename, media_type, byte_size, status, next_attempt_at)
                VALUES (:id, :document, :version, :actor, :blob, :sha,
                        'source.md', 'text/markdown', :size, 'SUCCEEDED', :at)
                """).param("id", jobId).param("document", documentId).param("version", versionId)
                .param("actor", actorId).param("blob", objectKey).param("sha", sha)
                .param("size", SOURCE.length).param("at", at).update();
        jdbc.sql("""
                INSERT INTO canonical_document
                    (version_id, source_filename, media_type, page_count, parsed_at)
                VALUES (:version, 'source.md', 'text/markdown', 1, :at)
                """).param("version", versionId).param("at", at).update();
        insertNode("n3", "第三段", 2);
        insertNode("n1", "第一段", 0);
        insertNode("n2", "第二段", 1);
    }

    private static void insertNode(String nodeId, String text, int stableOrder) {
        jdbc.sql("""
                INSERT INTO canonical_node
                    (version_id, node_id, kind, body_text, stable_order)
                VALUES (:version, :node, 'PARAGRAPH', :text, :stableOrder)
                """).param("version", versionId).param("node", nodeId).param("text", text)
                .param("stableOrder", stableOrder).update();
    }
}
