package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.retrieval.projection.ProjectionLeaseConflictException;
import com.yunjihai.rag.retrieval.projection.ProjectionScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcProjectionEventIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-30T08:00:00Z");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_projection_event_test")
            .withUsername("migration_owner")
            .withPassword("migration_test_only");

    private static JdbcClient jdbc;
    private static JdbcProjectionEventRepository repository;
    private UUID spaceId;
    private UUID documentId;
    private UUID versionId;

    @BeforeAll
    static void startDatabase() throws Exception {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_projection_event_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only"));
        repository = new JdbcProjectionEventRepository(jdbc);
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @BeforeEach
    void seedScope() {
        jdbc.sql("DELETE FROM retrieval_projection_event").update();
        jdbc.sql("DELETE FROM document_retrieval_metadata").update();
        jdbc.sql("UPDATE document SET current_published_version_id = NULL").update();
        jdbc.sql("DELETE FROM document_version").update();
        jdbc.sql("DELETE FROM document").update();
        jdbc.sql("DELETE FROM knowledge_space").update();
        jdbc.sql("DELETE FROM app_user").update();

        UUID actor = UUID.randomUUID();
        spaceId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :external, 'Actor')")
                .param("id", actor).param("external", actor.toString()).update();
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by) VALUES (:id, 'Space', :actor)")
                .param("id", spaceId).param("actor", actor).update();
        jdbc.sql("INSERT INTO document (id, space_id, title) VALUES (:id, :space, 'Document')")
                .param("id", documentId).param("space", spaceId).update();
        jdbc.sql("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_blob_id, created_by)
                VALUES (:id, :document, 1, 'blob', :actor)
                """).param("id", versionId).param("document", documentId).param("actor", actor).update();
    }

    @Test
    void claimsDisjointBatchesAndExposesMonotonicReplaySequence() {
        long documentSequence = repository.enqueueDocument(
                spaceId, documentId, versionId, 7, NOW);
        long spaceSequence = repository.enqueueSpace(spaceId, NOW.plusSeconds(1));

        var first = repository.claimBatch("worker-a", 1, NOW.plusSeconds(2), NOW.plusSeconds(32));
        var second = repository.claimBatch("worker-b", 1, NOW.plusSeconds(2), NOW.plusSeconds(32));

        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertNotEquals(first.getFirst().eventId(), second.getFirst().eventId());
        assertTrue(documentSequence < spaceSequence);
        assertEquals(spaceSequence, repository.currentSequence());
        assertEquals(2, repository.eventsAfter(0, 10).size());
        assertEquals(1, repository.eventsAfter(documentSequence, 10).size());
        assertEquals(ProjectionScope.DOCUMENT, first.getFirst().scope());
    }

    @Test
    void tokenBoundTransitionsRejectStaleOwnersAndPersistSpaceCheckpoint() {
        repository.enqueueSpace(spaceId, NOW);
        var lease = repository.claimBatch("worker-a", 1, NOW, NOW.plusSeconds(30)).getFirst();
        UUID cursor = UUID.randomUUID();

        repository.checkpoint(lease.eventId(), lease.leaseToken(), cursor,
                NOW.plusSeconds(1), NOW.plusSeconds(61));
        assertEquals(cursor, jdbc.sql("SELECT progress_document_id FROM retrieval_projection_event")
                .query(UUID.class).single());
        assertThrows(ProjectionLeaseConflictException.class,
                () -> repository.complete(lease.eventId(), UUID.randomUUID(), NOW.plusSeconds(2)));
        repository.complete(lease.eventId(), lease.leaseToken(), NOW.plusSeconds(2));
        assertThrows(ProjectionLeaseConflictException.class,
                () -> repository.complete(lease.eventId(), lease.leaseToken(), NOW.plusSeconds(3)));
    }

    @Test
    void expiredLeaseIsRecoveredWithNewTokenAndIncrementedAttempt() {
        repository.enqueueDocument(spaceId, documentId, versionId, 1, NOW);
        var first = repository.claimBatch("worker-a", 1, NOW, NOW.plusSeconds(30)).getFirst();

        assertTrue(repository.claimBatch("worker-b", 1, NOW.plusSeconds(29), NOW.plusSeconds(60)).isEmpty());
        var recovered = repository.claimBatch(
                "worker-b", 1, NOW.plusSeconds(30), NOW.plusSeconds(60)).getFirst();

        assertEquals(first.eventId(), recovered.eventId());
        assertNotEquals(first.leaseToken(), recovered.leaseToken());
        assertEquals(2, recovered.attempt());
        assertThrows(ProjectionLeaseConflictException.class,
                () -> repository.complete(first.eventId(), first.leaseToken(), NOW.plusSeconds(31)));
    }

    @Test
    void retryWaitsUntilScheduledTimeAndFailureUsesStableCode() {
        repository.enqueueDocument(spaceId, documentId, versionId, 1, NOW);
        var lease = repository.claimBatch("worker-a", 1, NOW, NOW.plusSeconds(30)).getFirst();

        repository.retry(lease.eventId(), lease.leaseToken(), "OPENSEARCH_UNAVAILABLE",
                NOW.plusSeconds(60), NOW.plusSeconds(1));
        assertTrue(repository.claimBatch("worker-b", 1, NOW.plusSeconds(59), NOW.plusSeconds(90)).isEmpty());
        var retried = repository.claimBatch("worker-b", 1, NOW.plusSeconds(60), NOW.plusSeconds(90)).getFirst();
        repository.fail(retried.eventId(), retried.leaseToken(), "INVALID_PROJECTION", NOW.plusSeconds(61));

        assertEquals("FAILED", jdbc.sql("SELECT status FROM retrieval_projection_event")
                .query(String.class).single());
        assertEquals("INVALID_PROJECTION", jdbc.sql("SELECT error_code FROM retrieval_projection_event")
                .query(String.class).single());
    }
}
