package com.yunjihai.rag.infrastructure.postgres;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
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
    private static final int CURRENT_MIGRATION_COUNT = 11;
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_test").withUsername("migration_owner").withPassword("migration_test_only");
    private static final String RUNTIME_USER = "rag_runtime_test";
    private static final String RUNTIME_PASSWORD = "runtime_test_only";

    @BeforeAll
    static void startPostgres() throws SQLException {
        POSTGRES.start();
        sql("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
        sql("ALTER DATABASE rag_test SET rag.runtime_role = 'rag_runtime_test'");
        sql("REVOKE TEMPORARY ON DATABASE rag_test FROM PUBLIC");
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void emptyDatabase() throws SQLException {
        sql("REVOKE TEMPORARY ON DATABASE rag_test FROM rag_runtime_test");
        sql("DROP SCHEMA public CASCADE");
        sql("CREATE SCHEMA public");
        sql("GRANT USAGE ON SCHEMA public TO rag_runtime_test");
    }

    @Test
    void migratesAnEmptyPostgresDatabaseWithAllFoundationTablesAndConstraints() throws SQLException {
        migrate();
        assertEquals(Set.of("app_user", "org_unit", "org_membership", "knowledge_space", "document",
                "document_version", "active_blob_reservation", "acl_entry", "ingestion_job",
                "canonical_document", "canonical_node", "audit_event", "blob_cleanup_candidate",
                "retrieval_projection_event", "document_retrieval_metadata", "external_identity",
                "local_credential", "login_source_attempt", "system_role_assignment",
                "space_role_assignment", "idempotency_record", "document_review",
                "qa_request", "qa_evidence_snapshot", "qa_answer", "qa_model_call_audit",
                "qa_evaluation_run", "qa_actor_rate_limit", "solution", "solution_requirement_version",
                "solution_requirement_item", "solution_requirement_source", "solution_outline_version",
                "solution_outline_node", "solution_outline_node_risk", "solution_adopted_section",
                "solution_command_idempotency", "solution_generation_attempt", "solution_section_version",
                "solution_section_claim", "solution_section_claim_evidence", "solution_section_claim_risk"),
                values("SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'"));
        assertColumnType("app_user", "id", "uuid");
        assertColumnType("app_user", "created_at", "timestamp with time zone");
        assertColumnType("org_unit", "parent_id", "uuid");
        assertColumnType("org_membership", "snapshot_at", "timestamp with time zone");
        assertColumnType("knowledge_space", "created_by", "uuid");
        assertColumnType("document", "id", "uuid");
        assertColumnType("document", "current_published_version_id", "uuid");
        assertColumnType("document", "revision", "bigint");
        assertColumnType("document", "updated_at", "timestamp with time zone");
        assertColumnType("document_version", "document_id", "uuid");
        assertColumnType("document_version", "created_at", "timestamp with time zone");
        assertColumnType("active_blob_reservation", "version_id", "uuid");
        assertColumnType("acl_entry", "principal_id", "uuid");
        assertColumnType("acl_entry", "expires_at", "timestamp with time zone");
        assertColumnType("ingestion_job", "lease_token", "uuid");
        assertColumnType("ingestion_job", "lease_expires_at", "timestamp with time zone");
        assertColumnType("ingestion_job", "sha256", "text");
        assertColumnType("canonical_document", "parsed_at", "timestamp with time zone");
        assertColumnType("canonical_node", "bounding_box", "jsonb");
        assertColumnType("canonical_node", "table_cells", "jsonb");
        assertColumnType("audit_event", "metadata", "jsonb");
        assertColumnType("audit_event", "occurred_at", "timestamp with time zone");
        assertColumnType("blob_cleanup_candidate", "registered_at", "timestamp with time zone");
        assertColumnType("retrieval_projection_event", "sequence", "bigint");
        assertColumnType("retrieval_projection_event", "lease_token", "uuid");
        assertColumnType("retrieval_projection_event", "next_attempt_at", "timestamp with time zone");
        assertColumnType("document_retrieval_metadata", "year", "integer");
        assertConstraint("document", "FOREIGN KEY (id, current_published_version_id) REFERENCES document_version(document_id, id)");
        assertConstraint("document_version", "UNIQUE (document_id, id)");
        assertConstraint("document_version", "UNIQUE (document_id, version_number)");
        assertConstraint("org_unit", "FOREIGN KEY (parent_id) REFERENCES org_unit(id)");
        assertConstraint("org_membership", "FOREIGN KEY (user_id) REFERENCES app_user(id)");
        assertConstraint("org_membership", "FOREIGN KEY (org_unit_id) REFERENCES org_unit(id)");
        assertConstraint("knowledge_space", "FOREIGN KEY (created_by) REFERENCES app_user(id)");
        assertConstraint("document", "FOREIGN KEY (space_id) REFERENCES knowledge_space(id)");
        assertConstraint("document_version", "FOREIGN KEY (document_id) REFERENCES document(id)");
        assertConstraint("active_blob_reservation", "FOREIGN KEY (space_id, document_id) REFERENCES document(space_id, id)");
        assertConstraint("active_blob_reservation", "FOREIGN KEY (document_id, version_id) REFERENCES document_version(document_id, id)");
        assertConstraint("acl_entry", "FOREIGN KEY (space_id, document_id) REFERENCES document(space_id, id)");
        assertConstraint("ingestion_job", "FOREIGN KEY (document_id, version_id) REFERENCES document_version(document_id, id)");
        assertConstraint("canonical_document", "FOREIGN KEY (version_id) REFERENCES document_version(id)");
        assertConstraint("canonical_node", "FOREIGN KEY (version_id) REFERENCES canonical_document(version_id)");
        assertIndex("acl_entry", "(principal_type, principal_id)", "revoked_at IS NULL");
        assertIndex("ingestion_job", "(status, next_attempt_at)", "QUEUED");
        assertIndex("ingestion_job", "(status, next_attempt_at)", "RETRY_WAIT");
        assertIndex("retrieval_projection_event", "(status, next_attempt_at, sequence)", "PENDING");
        assertIndex("retrieval_projection_event", "(lease_expires_at)", "LEASED");
        UUID cleanupId = UUID.randomUUID();
        sql("INSERT INTO blob_cleanup_candidate (id, bucket, sha256, object_key, status) VALUES ('"
                + cleanupId + "', 'rag-documents', '" + "b".repeat(64) + "', 'sha256/b/blob', 'DEFERRED')");
        sql("UPDATE blob_cleanup_candidate SET status = 'DELETED' WHERE id = '" + cleanupId + "'");
        sql("UPDATE blob_cleanup_candidate SET status = 'FAILED' WHERE id = '" + cleanupId + "'");
        assertThrows(SQLException.class, () -> sql("UPDATE blob_cleanup_candidate SET status = 'RETAINED' WHERE id = '" + cleanupId + "'"));
    }

    @Test
    void rejectsInvalidAclScopesDuplicateActiveGrantsAndInvalidStates() throws SQLException {
        migrate();
        UUID user = UUID.randomUUID(), space = UUID.randomUUID(), document = UUID.randomUUID(), version = UUID.randomUUID();
        sql("INSERT INTO app_user (id, external_id, display_name) VALUES ('" + user + "', 'acl-owner', 'Owner')");
        sql("INSERT INTO knowledge_space (id, name, created_by) VALUES ('" + space + "', 'S', '" + user + "')");
        sql("INSERT INTO document (id, space_id, title) VALUES ('" + document + "', '" + space + "', 'D')");
        sql("INSERT INTO document_version (id, document_id, version_number, source_blob_id, created_by) VALUES ('" + version + "', '" + document + "', 1, 'blob', '" + user + "')");

        sql("INSERT INTO acl_entry (id, space_id, scope, principal_type, principal_id) VALUES ('" + UUID.randomUUID() + "', '" + space + "', 'SPACE', 'USER', '" + user + "')");
        PSQLException duplicateGrant = assertThrows(PSQLException.class, () -> sql(
                "INSERT INTO acl_entry (id, space_id, scope, principal_type, principal_id) VALUES ('" + UUID.randomUUID() + "', '" + space + "', 'SPACE', 'USER', '" + user + "')"));
        assertEquals("acl_active_space_unique", duplicateGrant.getServerErrorMessage().getConstraint());
        sql("INSERT INTO acl_entry (id, space_id, document_id, scope, principal_type, principal_id) VALUES ('" + UUID.randomUUID() + "', '" + space + "', '" + document + "', 'DOCUMENT', 'USER', '" + user + "')");
        PSQLException duplicateDocumentGrant = assertThrows(PSQLException.class, () -> sql(
                "INSERT INTO acl_entry (id, space_id, document_id, scope, principal_type, principal_id) VALUES ('" + UUID.randomUUID() + "', '" + space + "', '" + document + "', 'DOCUMENT', 'USER', '" + user + "')"));
        assertEquals("acl_active_document_unique", duplicateDocumentGrant.getServerErrorMessage().getConstraint());
        assertCheckViolation("INSERT INTO acl_entry (id, space_id, document_id, scope, principal_type, principal_id) VALUES ('" + UUID.randomUUID() + "', '" + space + "', '" + document + "', 'SPACE', 'USER', '" + user + "')");
        assertCheckViolation("INSERT INTO acl_entry (id, space_id, scope, principal_type, principal_id) VALUES ('" + UUID.randomUUID() + "', '" + space + "', 'DOCUMENT', 'USER', '" + user + "')");
        assertCheckViolation("INSERT INTO acl_entry (id, space_id, scope, principal_type, principal_id) VALUES ('" + UUID.randomUUID() + "', '" + space + "', 'OTHER', 'USER', '" + user + "')");
        assertCheckViolation("INSERT INTO acl_entry (id, space_id, scope, principal_type, principal_id) VALUES ('" + UUID.randomUUID() + "', '" + space + "', 'SPACE', 'INVALID', '" + user + "')");
        assertCheckViolation("UPDATE document_version SET status = 'INVALID' WHERE id = '" + version + "'");
        assertCheckViolation("UPDATE document_version SET parse_status = 'INVALID' WHERE id = '" + version + "'");
        for (String status : java.util.List.of("DRAFT", "IN_REVIEW", "PUBLISHED", "ARCHIVED")) {
            sql("UPDATE document_version SET status = '" + status + "' WHERE id = '" + version + "'");
        }
        for (String status : java.util.List.of("PENDING", "SUCCEEDED", "FAILED")) {
            sql("UPDATE document_version SET parse_status = '" + status + "' WHERE id = '" + version + "'");
        }
        assertCheckViolation("UPDATE document SET revision = -1 WHERE id = '" + document + "'");
        String jobPrefix = "INSERT INTO ingestion_job (id, document_id, version_id, requested_by, source_blob_id, sha256, source_filename, media_type, byte_size, status) VALUES ('";
        String jobSuffix = "', '" + document + "', '" + version + "', '" + user + "', 'blob', '" + "a".repeat(64) + "', 'file.txt', 'text/plain', 1, '";
        assertCheckViolation(jobPrefix + UUID.randomUUID() + jobSuffix + "INVALID')");
        assertCheckViolation(jobPrefix + UUID.randomUUID() + jobSuffix + "LEASED')");
        for (String status : java.util.List.of("QUEUED", "RETRY_WAIT", "SUCCEEDED", "FAILED")) {
            sql(jobPrefix + UUID.randomUUID() + jobSuffix + status + "')");
        }
        sql("INSERT INTO ingestion_job (id, document_id, version_id, requested_by, source_blob_id, sha256, source_filename, media_type, byte_size, status, lease_token, lease_worker, lease_expires_at) VALUES ('"
                + UUID.randomUUID() + "', '" + document + "', '" + version + "', '" + user
                + "', 'blob', '" + "b".repeat(64) + "', 'file.txt', 'text/plain', 1, 'LEASED', '" + UUID.randomUUID() + "', 'worker', now())");
        assertCheckViolation("INSERT INTO ingestion_job (id, document_id, version_id, requested_by, source_blob_id, sha256, source_filename, media_type, byte_size) VALUES ('"
                + UUID.randomUUID() + "', '" + document + "', '" + version + "', '" + user
                + "', 'blob', 'not-a-sha', 'file.txt', 'text/plain', 1)");
    }

    @Test
    void rejectsDuplicateExternalIdsDocumentVersionsAndActiveBlobReservations() throws SQLException {
        migrate();
        UUID user = UUID.randomUUID(), space = UUID.randomUUID(), document = UUID.randomUUID(), version = UUID.randomUUID();
        UUID secondDocument = UUID.randomUUID(), secondVersion = UUID.randomUUID();
        sql("INSERT INTO app_user (id, external_id, display_name) VALUES ('" + user + "', 'external-1', 'A')");
        assertThrows(SQLException.class, () -> sql("INSERT INTO app_user (id, external_id, display_name) VALUES ('" + UUID.randomUUID() + "', 'external-1', 'B')"));
        sql("INSERT INTO org_unit (id, external_id, name) VALUES ('" + UUID.randomUUID() + "', 'department-1', 'A')");
        assertThrows(SQLException.class, () -> sql("INSERT INTO org_unit (id, external_id, name) VALUES ('" + UUID.randomUUID() + "', 'department-1', 'B')"));
        sql("INSERT INTO knowledge_space (id, name, created_by) VALUES ('" + space + "', 'S', '" + user + "')");
        sql("INSERT INTO document (id, space_id, title) VALUES ('" + document + "', '" + space + "', 'D')");
        sql("INSERT INTO document (id, space_id, title) VALUES ('" + secondDocument + "', '" + space + "', 'E')");
        sql("INSERT INTO document_version (id, document_id, version_number, source_blob_id, created_by) VALUES ('" + version + "', '" + document + "', 1, 'blob-1', '" + user + "')");
        sql("INSERT INTO document_version (id, document_id, version_number, source_blob_id, created_by) VALUES ('" + secondVersion + "', '" + secondDocument + "', 1, 'blob-3', '" + user + "')");
        assertThrows(SQLException.class, () -> sql("INSERT INTO document_version (id, document_id, version_number, source_blob_id, created_by) VALUES ('" + UUID.randomUUID() + "', '" + document + "', 1, 'blob-2', '" + user + "')"));
        sql("INSERT INTO active_blob_reservation (space_id, sha256, document_id, version_id) VALUES ('" + space + "', '" + "a".repeat(64) + "', '" + document + "', '" + version + "')");
        PSQLException duplicateReservation = assertThrows(PSQLException.class,
                () -> sql("INSERT INTO active_blob_reservation (space_id, sha256, document_id, version_id) VALUES ('"
                        + space + "', '" + "a".repeat(64) + "', '" + secondDocument + "', '" + secondVersion + "')"));
        assertEquals("23505", duplicateReservation.getSQLState());
        assertEquals("active_blob_reservation_pkey", duplicateReservation.getServerErrorMessage().getConstraint());
    }

    @Test
    void createsCoreManagementTablesAndConstraints() throws SQLException {
        migrate();

        assertTrue(values("SELECT tablename FROM pg_tables WHERE schemaname = 'public'").containsAll(Set.of(
                "external_identity", "local_credential", "login_source_attempt",
                "system_role_assignment", "space_role_assignment", "idempotency_record",
                "document_review")));
        assertColumnType("external_identity", "user_id", "uuid");
        assertColumnType("local_credential", "locked_until", "timestamp with time zone");
        assertColumnType("login_source_attempt", "source_hash", "text");
        assertColumnType("knowledge_space", "revision", "bigint");
        assertColumnType("document", "classification", "text");
        assertColumnType("document", "tags", "ARRAY");
        assertConstraint("external_identity", "FOREIGN KEY (user_id) REFERENCES app_user(id)");
        assertConstraint("space_role_assignment", "FOREIGN KEY (space_id) REFERENCES knowledge_space(id)");
        assertConstraint("document_review", "FOREIGN KEY (document_id, version_id) REFERENCES document_version(document_id, id)");
        assertIndex("system_role_assignment", "(user_id, role)", "revoked_at IS NULL");
        assertIndex("space_role_assignment", "(space_id, user_id, role)", "revoked_at IS NULL");

        for (String table : java.util.List.of(
                "external_identity", "local_credential", "login_source_attempt",
                "system_role_assignment", "space_role_assignment", "idempotency_record")) {
            assertEquals("t", scalar("SELECT has_table_privilege('rag_runtime_test', '" + table
                    + "', 'SELECT,INSERT,UPDATE,DELETE')"), table);
        }
        assertEquals("t", scalar("SELECT has_table_privilege('rag_runtime_test', 'document_review', 'SELECT,INSERT')"));
        assertEquals("f", scalar("SELECT has_table_privilege('rag_runtime_test', 'document_review', 'UPDATE')"));
        assertEquals("f", scalar("SELECT has_table_privilege('rag_runtime_test', 'document_review', 'DELETE')"));
    }

    @Test
    void rejectsInvalidRoleAndDuplicateExternalIdentity() throws SQLException {
        migrate();
        UUID actor = UUID.randomUUID(), member = UUID.randomUUID(), space = UUID.randomUUID();
        sql("INSERT INTO app_user (id, external_id, display_name) VALUES ('" + actor + "', 'actor', 'Actor')");
        sql("INSERT INTO app_user (id, external_id, display_name) VALUES ('" + member + "', 'member', 'Member')");
        sql("INSERT INTO knowledge_space (id, name, created_by) VALUES ('" + space + "', 'S', '" + actor + "')");

        sql("INSERT INTO external_identity (id, user_id, provider, subject) VALUES ('" + UUID.randomUUID()
                + "', '" + member + "', 'LOCAL', 'member')");
        PSQLException duplicateIdentity = assertThrows(PSQLException.class, () -> sql(
                "INSERT INTO external_identity (id, user_id, provider, subject) VALUES ('" + UUID.randomUUID()
                        + "', '" + actor + "', 'LOCAL', 'member')"));
        assertEquals("23505", duplicateIdentity.getSQLState());

        assertCheckViolation("INSERT INTO system_role_assignment (id, user_id, role, granted_by) VALUES ('"
                + UUID.randomUUID() + "', '" + member + "', 'UNKNOWN', '" + actor + "')");
        assertCheckViolation("INSERT INTO space_role_assignment (id, space_id, user_id, role, granted_by) VALUES ('"
                + UUID.randomUUID() + "', '" + space + "', '" + member + "', 'UNKNOWN', '" + actor + "')");
        sql("INSERT INTO space_role_assignment (id, space_id, user_id, role, granted_by) VALUES ('"
                + UUID.randomUUID() + "', '" + space + "', '" + member + "', 'READER', '" + actor + "')");
        PSQLException duplicateRole = assertThrows(PSQLException.class, () -> sql(
                "INSERT INTO space_role_assignment (id, space_id, user_id, role, granted_by) VALUES ('"
                        + UUID.randomUUID() + "', '" + space + "', '" + member + "', 'READER', '" + actor + "')"));
        assertEquals("space_role_active_unique", duplicateRole.getServerErrorMessage().getConstraint());
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
    void migrationGrantsRuntimeTemporaryTablesWithoutPermanentSchemaPrivileges() throws SQLException {
        assertEquals("f", scalar("SELECT has_database_privilege('rag_runtime_test', current_database(), 'TEMPORARY')"));
        try (Connection connection = runtimeConnection(); Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.execute("CREATE TEMP TABLE before_migration (id integer)"));
        }

        migrate();

        assertEquals("t", scalar("SELECT has_database_privilege('rag_runtime_test', current_database(), 'TEMPORARY')"));
        try (Connection connection = runtimeConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE runtime_snapshot_ids (id integer) ON COMMIT DROP");
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
        assertEquals(CURRENT_MIGRATION_COUNT, restartedApplication.info().applied().length);
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
        assertEquals(CURRENT_MIGRATION_COUNT, flyway("classpath:db/migration").migrate().migrationsExecuted);
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

    private static void assertColumnType(String table, String column, String expectedType) throws SQLException {
        assertEquals(expectedType, scalar("SELECT data_type FROM information_schema.columns WHERE table_schema = 'public'"
                + " AND table_name = '" + table + "' AND column_name = '" + column + "'"), table + "." + column);
    }

    private static void assertConstraint(String table, String expectedDefinition) throws SQLException {
        assertTrue(definitions("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'public."
                + table + "'::regclass").stream().anyMatch(definition -> definition.contains(expectedDefinition)),
                table + " must have " + expectedDefinition);
    }

    private static void assertIndex(String table, String columns, String predicate) throws SQLException {
        assertTrue(definitions("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = '"
                + table + "'").stream().anyMatch(definition -> definition.contains(columns) && definition.contains(predicate)),
                table + " must index " + columns + " with " + predicate);
    }

    private static void assertCheckViolation(String command) {
        PSQLException failure = assertThrows(PSQLException.class, () -> sql(command));
        assertEquals("23514", failure.getSQLState());
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
