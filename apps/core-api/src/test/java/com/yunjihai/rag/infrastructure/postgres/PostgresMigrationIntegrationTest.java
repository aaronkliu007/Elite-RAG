package com.yunjihai.rag.infrastructure.postgres;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PostgresMigrationIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_test").withUsername("migration_owner").withPassword("migration_test_only");
    private static final String RUNTIME_USER = "rag_runtime_test";
    private static final String RUNTIME_PASSWORD = "runtime_test_only";

    @BeforeAll
    static void startPostgres() throws SQLException {
        POSTGRES.start();
        sql("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
        sql("ALTER DATABASE rag_test SET rag.runtime_role = 'rag_runtime_test'");
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void emptyDatabase() throws SQLException {
        sql("DROP SCHEMA public CASCADE");
        sql("CREATE SCHEMA public");
        sql("GRANT USAGE ON SCHEMA public TO rag_runtime_test");
    }

    @Test
    void migratesAnEmptyPostgresDatabaseWithAllFoundationTablesAndConstraints() throws SQLException {
        migrate();
        assertEquals(Set.of("app_user", "org_unit", "org_membership", "knowledge_space", "document",
                "document_version", "active_blob_reservation", "acl_entry", "ingestion_job",
                "canonical_document", "canonical_node", "audit_event", "blob_cleanup_candidate"),
                values("SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'"));
        assertEquals("uuid", scalar("SELECT data_type FROM information_schema.columns WHERE table_name = 'document' AND column_name = 'id'"));
        assertEquals("timestamp with time zone", scalar("SELECT data_type FROM information_schema.columns WHERE table_name = 'document_version' AND column_name = 'created_at'"));
        assertEquals("jsonb", scalar("SELECT data_type FROM information_schema.columns WHERE table_name = 'canonical_node' AND column_name = 'bounding_box'"));
        assertEquals("bigint", scalar("SELECT data_type FROM information_schema.columns WHERE table_name = 'document' AND column_name = 'revision'"));
        assertTrue(definitions("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'document'::regclass").stream()
                .anyMatch(definition -> definition.contains("FOREIGN KEY (id, current_published_version_id)")));
        assertTrue(definitions("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'document_version'::regclass").stream()
                .anyMatch(definition -> definition.contains("UNIQUE (document_id, id)")));
        assertTrue(definitions("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'org_unit'::regclass").stream()
                .anyMatch(definition -> definition.contains("FOREIGN KEY (parent_id)")));
        assertTrue(definitions("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'acl_entry'::regclass").stream()
                .anyMatch(definition -> definition.startsWith("CHECK")));
        assertTrue(definitions("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'ingestion_job'::regclass").stream()
                .anyMatch(definition -> definition.startsWith("CHECK")));
        assertTrue(definitions("SELECT indexdef FROM pg_indexes WHERE tablename = 'acl_entry'").stream()
                .anyMatch(definition -> definition.contains("(principal_type, principal_id)")));
        assertTrue(definitions("SELECT indexdef FROM pg_indexes WHERE tablename = 'ingestion_job'").stream()
                .anyMatch(definition -> definition.contains("(status, next_attempt_at)")));
        UUID cleanupId = UUID.randomUUID();
        sql("INSERT INTO blob_cleanup_candidate (id, bucket, sha256, object_key, status) VALUES ('"
                + cleanupId + "', 'rag-documents', '" + "b".repeat(64) + "', 'sha256/b/blob', 'DEFERRED')");
        sql("UPDATE blob_cleanup_candidate SET status = 'DELETED' WHERE id = '" + cleanupId + "'");
        sql("UPDATE blob_cleanup_candidate SET status = 'FAILED' WHERE id = '" + cleanupId + "'");
        assertThrows(SQLException.class, () -> sql("UPDATE blob_cleanup_candidate SET status = 'RETAINED' WHERE id = '" + cleanupId + "'"));
    }

    @Test
    void rejectsDuplicateExternalIdsDocumentVersionsAndActiveBlobReservations() throws SQLException {
        migrate();
        UUID user = UUID.randomUUID(), space = UUID.randomUUID(), document = UUID.randomUUID(), version = UUID.randomUUID();
        sql("INSERT INTO app_user (id, external_id, display_name) VALUES ('" + user + "', 'external-1', 'A')");
        assertThrows(SQLException.class, () -> sql("INSERT INTO app_user (id, external_id, display_name) VALUES ('" + UUID.randomUUID() + "', 'external-1', 'B')"));
        sql("INSERT INTO knowledge_space (id, name, created_by) VALUES ('" + space + "', 'S', '" + user + "')");
        sql("INSERT INTO document (id, space_id, title) VALUES ('" + document + "', '" + space + "', 'D')");
        sql("INSERT INTO document_version (id, document_id, version_number, source_blob_id, created_by) VALUES ('" + version + "', '" + document + "', 1, 'blob-1', '" + user + "')");
        assertThrows(SQLException.class, () -> sql("INSERT INTO document_version (id, document_id, version_number, source_blob_id, created_by) VALUES ('" + UUID.randomUUID() + "', '" + document + "', 1, 'blob-2', '" + user + "')"));
        sql("INSERT INTO active_blob_reservation (space_id, sha256, document_id, version_id) VALUES ('" + space + "', '" + "a".repeat(64) + "', '" + document + "', '" + version + "')");
        assertThrows(SQLException.class, () -> sql("INSERT INTO active_blob_reservation (space_id, sha256, document_id, version_id) VALUES ('" + space + "', '" + "a".repeat(64) + "', '" + document + "', '" + version + "')"));
    }

    @Test
    void rejectsPublishedVersionFromAnotherDocument() throws SQLException {
        migrate();
        UUID user = UUID.randomUUID(), space = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID(), version = UUID.randomUUID();
        sql("INSERT INTO app_user (id, external_id, display_name) VALUES ('" + user + "', 'owner', 'Owner')");
        sql("INSERT INTO knowledge_space (id, name, created_by) VALUES ('" + space + "', 'S', '" + user + "')");
        sql("INSERT INTO document (id, space_id, title) VALUES ('" + first + "', '" + space + "', 'A')");
        sql("INSERT INTO document (id, space_id, title) VALUES ('" + second + "', '" + space + "', 'B')");
        sql("INSERT INTO document_version (id, document_id, version_number, source_blob_id, created_by) VALUES ('" + version + "', '" + first + "', 1, 'blob', '" + user + "')");
        assertThrows(SQLException.class, () -> sql("UPDATE document SET current_published_version_id = '" + version + "' WHERE id = '" + second + "'"));
        sql("UPDATE document SET current_published_version_id = '" + version + "' WHERE id = '" + first + "'");
    }

    @Test
    void runningRoleCannotCreateOrAlterSchema() throws SQLException {
        migrate();
        try (Connection connection = runtimeConnection(); Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.execute("CREATE TABLE forbidden (id integer)"));
            assertThrows(SQLException.class, () -> statement.execute("ALTER TABLE document ADD COLUMN forbidden integer"));
        }
    }

    @Test
    void runningRoleCannotUpdateOrDeleteAuditEvents() throws SQLException {
        migrate();
        UUID event = UUID.randomUUID();
        try (Connection connection = runtimeConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO audit_event (id, actor_id, action, resource_type, resource_id, result) VALUES ('" + event + "', '" + UUID.randomUUID() + "', 'CREATE', 'DOCUMENT', '" + UUID.randomUUID() + "', 'SUCCESS')");
            assertThrows(SQLException.class, () -> statement.execute("UPDATE audit_event SET action = 'CHANGED' WHERE id = '" + event + "'"));
            assertThrows(SQLException.class, () -> statement.execute("DELETE FROM audit_event WHERE id = '" + event + "'"));
        }
    }

    @Test
    void flywayValidateSucceedsAfterRestart() {
        migrate();
        Flyway restartedApplication = flyway("classpath:db/migration");
        assertDoesNotThrow(restartedApplication::validate);
        assertEquals(1, restartedApplication.info().applied().length);
    }

    @Test
    void flywayValidateRejectsMissingOutOfOrderOrChecksumChangedMigration() throws IOException {
        migrate();
        Path missing = Files.createTempDirectory("flyway-missing-");
        assertThrows(FlywayException.class, () -> flyway("filesystem:" + missing).validate());
        Path outOfOrder = Files.createTempDirectory("flyway-out-of-order-");
        Files.writeString(outOfOrder.resolve("V001__foundation.sql"), migrationText());
        Files.writeString(outOfOrder.resolve("V000__earlier.sql"), "SELECT 1;\n");
        assertThrows(FlywayException.class, () -> flyway("filesystem:" + outOfOrder).validate());
        Path changed = Files.createTempDirectory("flyway-changed-");
        Files.writeString(changed.resolve("V001__foundation.sql"), migrationText() + "\nSELECT 1;\n");
        assertThrows(FlywayException.class, () -> flyway("filesystem:" + changed).validate());
    }

    private static String migrationText() throws IOException {
        try (var stream = PostgresMigrationIntegrationTest.class.getResourceAsStream("/db/migration/V001__foundation.sql")) {
            assertNotNull(stream, "V001 migration must be on the classpath");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Flyway flyway(String location) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(location).ignoreMigrationPatterns("").load();
    }

    private static void migrate() {
        assertEquals(1, flyway("classpath:db/migration").migrate().migrationsExecuted);
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection runtimeConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), RUNTIME_USER, RUNTIME_PASSWORD);
    }

    private static void sql(String command) throws SQLException {
        try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
            statement.execute(command);
        }
    }

    private static String scalar(String query) throws SQLException {
        try (Connection connection = ownerConnection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static Set<String> values(String query) throws SQLException {
        return new HashSet<>(definitions(query));
    }

    private static java.util.List<String> definitions(String query) throws SQLException {
        var values = new java.util.ArrayList<String>();
        try (Connection connection = ownerConnection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) {
            while (result.next()) values.add(result.getString(1));
        }
        return values;
    }
}
