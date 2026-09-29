package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.ingestion.DocumentVersionId;
import com.yunjihai.rag.ingestion.IngestionRequest;
import com.yunjihai.rag.ingestion.ParserError;
import com.yunjihai.rag.ingestion.ParserErrorCode;
import com.yunjihai.rag.knowledge.DocumentAggregate;
import com.yunjihai.rag.knowledge.DocumentStatus;
import com.yunjihai.rag.knowledge.DocumentVersion;
import com.yunjihai.rag.knowledge.KnowledgeSpace;
import com.yunjihai.rag.knowledge.ParseStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JdbcIngestionJobIntegrationTest extends IngestionPostgresFixture {
    @Test
    void arbitrarySourceBlobIdAndShaRoundTripWithoutConflation() {
        String sourceBlobId = "blob-801";
        String sha256 = "8".repeat(64);
        jdbc.sql("UPDATE document_version SET source_blob_id = :sourceBlobId WHERE id = :versionId")
                .param("sourceBlobId", sourceBlobId)
                .param("versionId", request.documentVersion().versionId())
                .update();
        var independent = new IngestionRequest(request.documentVersion(), actor, sourceBlobId,
                request.filename(), request.mediaType(), request.sizeBytes(), sha256);
        UUID jobId = UUID.randomUUID();

        jobs.enqueue(jobId, independent, AT);
        var claimed = jobs.claimNext("worker-a", AT, AT.plusSeconds(30)).orElseThrow();

        assertEquals(independent, claimed.request());
        assertEquals(sourceBlobId, ownerJdbc.sql("SELECT source_blob_id FROM ingestion_job WHERE id = :id")
                .param("id", jobId).query(String.class).single());
        assertEquals(sha256, ownerJdbc.sql("SELECT sha256 FROM ingestion_job WHERE id = :id")
                .param("id", jobId).query(String.class).single());
    }

    @Test
    void enqueueRejectsBlobNotOwnedByVersionBeforeCreatingJob() {
        var mismatched = new IngestionRequest(request.documentVersion(), actor, "blob-802",
                request.filename(), request.mediaType(), request.sizeBytes(), "8".repeat(64));

        var failure = assertThrows(RuntimeException.class,
                () -> jobs.enqueue(UUID.randomUUID(), mismatched, AT));

        assertEquals("INGESTION_REQUEST_CONFLICT", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0L, ownerJdbc.sql("SELECT count(*) FROM ingestion_job").query(Long.class).single());
        assertEquals(document.latestVersion().sourceBlobId(), ownerJdbc.sql(
                        "SELECT source_blob_id FROM document_version WHERE id = :id")
                .param("id", request.documentVersion().versionId()).query(String.class).single());
    }

    @Test
    void leaseRequiresPositiveDurationAfterPostgresMicrosecondNormalization() {
        UUID jobId = UUID.randomUUID();
        jobs.enqueue(jobId, request, AT);
        Instant withinSameMicrosecond = AT.plusNanos(100);
        Instant laterWithinSameMicrosecond = AT.plusNanos(900);

        assertThrows(IllegalArgumentException.class,
                () -> jobs.claimNext("worker-a", withinSameMicrosecond, laterWithinSameMicrosecond));
        assertEquals("QUEUED", text(jobId, "status"));

        Instant leaseUntil = AT.plusNanos(1_100);
        var lease = jobs.claimNext("worker-a", withinSameMicrosecond, leaseUntil).orElseThrow();
        assertEquals(leaseUntil.truncatedTo(ChronoUnit.MICROS), lease.leaseUntil());
        assertTrue(jobs.claimNext("worker-b", AT.plusNanos(999), AT.plusSeconds(1)).isEmpty());
        assertEquals(jobId, jobs.claimNext("worker-b", lease.leaseUntil(), AT.plusSeconds(1))
                .orElseThrow().jobId());
    }

    @Test
    void claimsEligibleJobsInStableFifoOrderAndHonorsNextAttemptAt() {
        var laterId = UUID.fromString("01995f10-0010-7000-8000-000000000002");
        var firstId = UUID.fromString("01995f10-0010-7000-8000-000000000001");
        var deferredId = UUID.fromString("01995f10-0010-7000-8000-000000000003");
        jobs.enqueue(laterId, request, AT);
        jobs.enqueue(firstId, request, AT);
        jobs.enqueue(deferredId, request, AT);
        jdbc.sql("UPDATE ingestion_job SET status = 'RETRY_WAIT', next_attempt_at = :at WHERE id = :id")
                .param("at", utc(AT.plusSeconds(60))).param("id", deferredId).update();

        var first = jobs.claimNext("worker-a", AT, AT.plusSeconds(30)).orElseThrow();
        assertEquals(firstId, first.jobId());
        assertEquals(AT.plusSeconds(30), first.leaseUntil());
        var second = jobs.claimNext("worker-b", AT, AT.plusSeconds(30)).orElseThrow();
        assertEquals(laterId, second.jobId());
        assertTrue(jobs.claimNext("worker-b", AT, AT.plusSeconds(30)).isEmpty());
        jobs.complete(first.jobId(), first.leaseToken(), AT.plusSeconds(1));
        jobs.complete(second.jobId(), second.leaseToken(), AT.plusSeconds(1));
        assertEquals(deferredId, jobs.claimNext("worker-b", AT.plusSeconds(60), AT.plusSeconds(90))
                .orElseThrow().jobId());
    }

    @Test
    void skipLockedClaimDoesNotWaitForOldestRowAndWorkersReceiveDifferentJobs() throws Exception {
        UUID first = UUID.fromString("01995f10-0010-7000-8000-000000000001");
        UUID second = UUID.fromString("01995f10-0010-7000-8000-000000000002");
        jobs.enqueue(first, request, AT);
        jobs.enqueue(second, request, AT.plusSeconds(1));

        try (Connection blocker = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only")) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT id FROM ingestion_job WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, first);
                statement.executeQuery();
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var claim = executor.submit(() -> jobs.claimNext("worker-b", AT.plusSeconds(2), AT.plusSeconds(32)));
                assertEquals(second, claim.get(3, TimeUnit.SECONDS).orElseThrow().jobId());
            }
            blocker.rollback();
        }
        assertEquals(first, jobs.claimNext("worker-a", AT.plusSeconds(2), AT.plusSeconds(32)).orElseThrow().jobId());
    }

    @Test
    void expiredLeaseIsReclaimedAtEqualityAndOldTokenIsRejected() {
        UUID jobId = UUID.randomUUID();
        jobs.enqueue(jobId, request, AT);
        var first = jobs.claimNext("worker-a", AT, AT.plusSeconds(30)).orElseThrow();

        assertTrue(jobs.claimNext("worker-b", AT.plusSeconds(29), AT.plusSeconds(60)).isEmpty());
        var replacement = jobs.claimNext("worker-b", AT.plusSeconds(30), AT.plusSeconds(60)).orElseThrow();
        assertEquals(jobId, replacement.jobId());
        assertEquals(2, replacement.attempt());
        assertNotEquals(first.leaseToken(), replacement.leaseToken());
        assertLeaseConflict(() -> jobs.complete(jobId, first.leaseToken(), AT.plusSeconds(31)));
        assertLeaseConflict(() -> jobs.retry(jobId, first.leaseToken(), retryable(), AT.plusSeconds(90), AT.plusSeconds(31)));
        assertLeaseConflict(() -> jobs.fail(jobId, first.leaseToken(), terminal(), AT.plusSeconds(31)));
        assertEquals("LEASED", text(jobId, "status"));
        assertEquals(replacement.leaseToken(), uuid(jobId, "lease_token"));
    }

    @Test
    void retryAndFinalFailurePersistOnlyWithAnActiveUnexpiredLease() {
        UUID retryId = UUID.randomUUID();
        jobs.enqueue(retryId, request, AT);
        var retryLease = jobs.claimNext("worker-a", AT, AT.plusSeconds(30)).orElseThrow();
        jobs.retry(retryId, retryLease.leaseToken(), retryable(), AT.plusSeconds(60), AT.plusSeconds(1));
        assertEquals("RETRY_WAIT", text(retryId, "status"));
        assertEquals("TIMEOUT", text(retryId, "error_code"));
        assertTrue(isNull(retryId, "lease_token"));
        assertEquals(AT.plusSeconds(60), instant(retryId, "next_attempt_at"));
        assertTrue(jobs.claimNext("worker-b", AT.plusSeconds(59), AT.plusSeconds(89)).isEmpty());
        var second = jobs.claimNext("worker-b", AT.plusSeconds(60), AT.plusSeconds(90)).orElseThrow();
        assertEquals(2, second.attempt());

        jobs.fail(retryId, second.leaseToken(), terminal(), AT.plusSeconds(61));
        assertEquals("FAILED", text(retryId, "status"));
        assertEquals("CONVERSION_FAILED", text(retryId, "error_code"));
        assertTrue(isNull(retryId, "lease_token"));

        UUID expiredId = UUID.randomUUID();
        jobs.enqueue(expiredId, request, AT.plusSeconds(100));
        var expired = jobs.claimNext("worker-a", AT.plusSeconds(100), AT.plusSeconds(110)).orElseThrow();
        assertLeaseConflict(() -> jobs.complete(expiredId, expired.leaseToken(), AT.plusSeconds(110)));
        assertEquals("LEASED", text(expiredId, "status"));
    }

    @Test
    void completeClearsLeaseAndCannotBeRepeated() {
        UUID jobId = UUID.randomUUID();
        jobs.enqueue(jobId, request, AT);
        var lease = jobs.claimNext("worker-a", AT, AT.plusSeconds(30)).orElseThrow();
        jobs.complete(jobId, lease.leaseToken(), AT.plusSeconds(1));
        assertEquals("SUCCEEDED", text(jobId, "status"));
        assertTrue(isNull(jobId, "lease_token"));
        assertLeaseConflict(() -> jobs.complete(jobId, lease.leaseToken(), AT.plusSeconds(2)));
    }

    private boolean isNull(UUID jobId, String column) {
        return ownerJdbc.sql("SELECT " + column + " IS NULL FROM ingestion_job WHERE id = :id")
                .param("id", jobId).query(Boolean.class).single();
    }

    private String text(UUID jobId, String column) {
        return ownerJdbc.sql("SELECT " + column + " FROM ingestion_job WHERE id = :id")
                .param("id", jobId).query(String.class).single();
    }

    private UUID uuid(UUID jobId, String column) {
        return ownerJdbc.sql("SELECT " + column + " FROM ingestion_job WHERE id = :id")
                .param("id", jobId).query(UUID.class).single();
    }

    private Instant instant(UUID jobId, String column) {
        return ownerJdbc.sql("SELECT " + column + " FROM ingestion_job WHERE id = :id")
                .param("id", jobId).query(OffsetDateTime.class).single().toInstant();
    }

    private static ParserError retryable() {
        return new ParserError(ParserErrorCode.TIMEOUT, "retry", true);
    }

    private static ParserError terminal() {
        return new ParserError(ParserErrorCode.CONVERSION_FAILED, "terminal", false);
    }

    private static void assertLeaseConflict(Runnable action) {
        var failure = assertThrows(RuntimeException.class, action::run);
        assertEquals("INGESTION_LEASE_CONFLICT", failure.getMessage());
        assertNull(failure.getCause());
    }
}

abstract class IngestionPostgresFixture {
    static final Instant AT = Instant.parse("2026-09-29T08:07:06.123456Z");
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_ingestion_test").withUsername("migration_owner").withPassword("migration_test_only");
    static JdbcClient jdbc;
    static JdbcClient ownerJdbc;
    static JdbcIngestionJobRepository jobs;
    static JdbcParsedDocumentStore parsed;
    UUID actor;
    UUID space;
    DocumentAggregate document;
    IngestionRequest request;

    @BeforeAll
    static void startDatabase() throws Exception {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_ingestion_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only"));
        ownerJdbc = JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jobs = new JdbcIngestionJobRepository(jdbc);
        parsed = new JdbcParsedDocumentStore(jdbc);
    }

    @BeforeEach
    void createDocumentFixture() {
        actor = UUID.randomUUID();
        space = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String hash = "a".repeat(64);
        String blob = "sha256/aa/" + hash;
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :externalId, 'Tester')")
                .param("id", actor).param("externalId", actor.toString()).update();
        new JdbcKnowledgeSpaceRepository(jdbc).insert(new KnowledgeSpace(space, "Engineering", actor, AT));
        document = new DocumentAggregate(documentId, space, "Manual", List.of(new DocumentVersion(
                versionId, 1, blob, DocumentStatus.DRAFT, ParseStatus.PENDING, actor, AT, actor, AT)),
                null, false, 0);
        new JdbcDocumentRepository(jdbc).insert(document);
        request = new IngestionRequest(new DocumentVersionId(documentId, versionId), actor, blob,
                "manual.pdf", "application/pdf", 1234, hash);
    }

    @AfterEach
    void clearDatabase() {
        ownerJdbc.sql("DROP TRIGGER IF EXISTS task6_failpoint ON canonical_document").update();
        ownerJdbc.sql("DROP TRIGGER IF EXISTS task6_failpoint ON canonical_node").update();
        ownerJdbc.sql("DROP TRIGGER IF EXISTS task6_failpoint ON document_version").update();
        ownerJdbc.sql("DROP TRIGGER IF EXISTS task6_failpoint ON ingestion_job").update();
        ownerJdbc.sql("DROP TRIGGER IF EXISTS task6_failpoint ON audit_event").update();
        ownerJdbc.sql("DROP FUNCTION IF EXISTS task6_failpoint() CASCADE").update();
        ownerJdbc.sql("DELETE FROM audit_event").update();
        jdbc.sql("DELETE FROM ingestion_job").update();
        jdbc.sql("DELETE FROM canonical_document").update();
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

    static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
