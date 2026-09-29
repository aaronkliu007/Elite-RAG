package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.ingestion.OrphanBlobCandidate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcBlobCleanupIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_blob_cleanup_test")
            .withUsername("migration_owner")
            .withPassword("migration_test_only");
    private static final String SHA = "1".repeat(64);
    private static final String KEY = "sha256/11/" + SHA;
    private static JdbcClient jdbc;
    private static JdbcClient ownerJdbc;
    private static JdbcOrphanBlobRecorder recorder;
    private static JdbcBlobCleanupEligibilityRepository eligibility;

    @BeforeAll
    static void startDatabase() throws Exception {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_blob_cleanup_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only"));
        ownerJdbc = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        recorder = new JdbcOrphanBlobRecorder(jdbc);
        eligibility = new JdbcBlobCleanupEligibilityRepository(jdbc);
    }

    @AfterEach
    void clearDatabase() {
        jdbc.sql("DELETE FROM blob_cleanup_candidate").update();
        jdbc.sql("DELETE FROM active_blob_reservation").update();
        jdbc.sql("UPDATE document SET current_published_version_id = NULL").update();
        jdbc.sql("DELETE FROM document_version").update();
        jdbc.sql("DELETE FROM document").update();
        jdbc.sql("DELETE FROM knowledge_space").update();
        jdbc.sql("DELETE FROM app_user").update();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void candidateRegistrationIsIdempotentAndNeverOverwritesOriginalDigest() {
        var candidate = new OrphanBlobCandidate("rag-documents", SHA, KEY);

        recorder.record(candidate);
        recorder.record(candidate);
        recorder.record(new OrphanBlobCandidate("rag-documents", "2".repeat(64), KEY));

        assertEquals(1L, count("blob_cleanup_candidate"));
        assertEquals(SHA, ownerJdbc.sql("SELECT sha256 FROM blob_cleanup_candidate")
                .query(String.class).single());
        assertEquals("PENDING", ownerJdbc.sql("SELECT status FROM blob_cleanup_candidate")
                .query(String.class).single());
    }

    @Test
    void candidateIsEligibleOnlyWhenNoVersionOrActiveReservationReferencesItsBlob() {
        var candidate = new OrphanBlobCandidate("rag-documents", SHA, KEY);
        assertTrue(eligibility.isEligible(candidate));

        Fixture first = insertVersion(KEY, true, "ARCHIVED");
        assertFalse(eligibility.isEligible(candidate), "归档文档及归档版本仍是不可删除的历史引用");
        remove(first);
        assertTrue(eligibility.isEligible(candidate));

        Fixture second = insertVersion("independent-source-blob", false, "DRAFT");
        jdbc.sql("INSERT INTO active_blob_reservation (space_id, sha256, document_id, version_id) "
                        + "VALUES (:space, :sha, :document, :version)")
                .param("space", second.space()).param("sha", SHA)
                .param("document", second.document()).param("version", second.version()).update();
        assertFalse(eligibility.isEligible(candidate), "活动预留独立于 source_blob_id 阻止删除");
        jdbc.sql("DELETE FROM active_blob_reservation").update();
        assertTrue(eligibility.isEligible(candidate));
    }

    private static Fixture insertVersion(String sourceBlobId, boolean archived, String versionStatus) {
        UUID actor = UUID.randomUUID();
        UUID space = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :external, 'Tester')")
                .param("id", actor).param("external", actor.toString()).update();
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by) VALUES (:id, 'Space', :actor)")
                .param("id", space).param("actor", actor).update();
        jdbc.sql("INSERT INTO document (id, space_id, title, archived) VALUES (:id, :space, 'Doc', :archived)")
                .param("id", document).param("space", space).param("archived", archived).update();
        jdbc.sql("INSERT INTO document_version "
                        + "(id, document_id, version_number, source_blob_id, status, created_by) "
                        + "VALUES (:id, :document, 1, :sourceBlobId, :status, :actor)")
                .param("id", version).param("document", document).param("sourceBlobId", sourceBlobId)
                .param("status", versionStatus).param("actor", actor).update();
        return new Fixture(actor, space, document, version);
    }

    private static void remove(Fixture fixture) {
        jdbc.sql("DELETE FROM document_version WHERE id = :id").param("id", fixture.version()).update();
        jdbc.sql("DELETE FROM document WHERE id = :id").param("id", fixture.document()).update();
        jdbc.sql("DELETE FROM knowledge_space WHERE id = :id").param("id", fixture.space()).update();
        jdbc.sql("DELETE FROM app_user WHERE id = :id").param("id", fixture.actor()).update();
    }

    private static long count(String table) {
        return ownerJdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private record Fixture(UUID actor, UUID space, UUID document, UUID version) {
    }
}
