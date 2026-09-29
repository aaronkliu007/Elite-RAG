package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.access.AccessDecisionService;
import com.yunjihai.rag.access.EffectivePrincipalSet;
import com.yunjihai.rag.audit.AuditEvent;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.knowledge.ConcurrentDocumentUpdateException;
import com.yunjihai.rag.knowledge.DocumentAggregate;
import com.yunjihai.rag.knowledge.DocumentContentGateway;
import com.yunjihai.rag.knowledge.DocumentService;
import com.yunjihai.rag.knowledge.DocumentStatus;
import com.yunjihai.rag.knowledge.DocumentVersion;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.knowledge.KnowledgeSpace;
import com.yunjihai.rag.knowledge.KnowledgeSpaceReadAuthorization;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import com.yunjihai.rag.knowledge.ParseStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JdbcDocumentRepositoryIntegrationTest extends DocumentPostgresFixture {
    @Test
    void spaceInsertPersistsIdentityActorAndTimestamp() {
        var space = new KnowledgeSpace(UUID.randomUUID(), "Engineering", actor, AT);
        spaces.insert(space);
        assertEquals(space.id(), jdbc.sql("SELECT id FROM knowledge_space WHERE id = :id")
                .param("id", space.id()).query(UUID.class).single());
        assertEquals(actor, jdbc.sql("SELECT created_by FROM knowledge_space WHERE id = :id")
                .param("id", space.id()).query(UUID.class).single());
        assertEquals(AT, jdbc.sql("SELECT created_at FROM knowledge_space WHERE id = :id")
                .param("id", space.id()).query(OffsetDateTime.class).single().toInstant());
    }

    @Test
    void roundTripsAllVersionFieldsAndPublishedPointerInOrder() {
        var space = createSpace();
        var first = version(1, 'a', DocumentStatus.PUBLISHED, ParseStatus.SUCCEEDED,
                actor, AT, null, null);
        var second = version(2, 'b', DocumentStatus.IN_REVIEW, ParseStatus.FAILED,
                actor, AT.plusSeconds(2), actor, AT.plusSeconds(3));
        var document = new DocumentAggregate(UUID.randomUUID(), space, "Manual", List.of(first, second),
                first.id(), false, 7);

        documents.insert(document);

        assertEquals(document, documents.findById(document.id()).orElseThrow());
        assertEquals(new DocumentVersionRef(document.id(), first.id()),
                documents.findActiveVersionBySpaceAndSourceBlob(space, blob('a')).orElseThrow());
        assertEquals(new DocumentVersionRef(document.id(), second.id()),
                documents.findActiveVersionBySpaceAndSourceBlob(space, blob('b')).orElseThrow());
        assertTrue(documents.findActiveVersionBySpaceAndSourceBlob(space, blob('c')).isEmpty());
        assertEquals(2L, count("active_blob_reservation"));
    }

    @Test
    void replaceAddsVersionAndArchiveRemovesReservationsButKeepsHistory() {
        var space = createSpace();
        var first = version(1, 'a', DocumentStatus.PUBLISHED, ParseStatus.SUCCEEDED,
                actor, AT, actor, AT.plusSeconds(1));
        var initial = new DocumentAggregate(UUID.randomUUID(), space, "Manual", List.of(first), first.id(), false, 0);
        documents.insert(initial);
        var second = version(2, 'b', DocumentStatus.DRAFT, ParseStatus.PENDING,
                actor, AT.plusSeconds(2), null, null);
        var updated = new DocumentAggregate(initial.id(), space, "Manual 2", List.of(first, second),
                first.id(), false, 1);
        documents.replace(updated, 0);
        assertEquals(updated, documents.findById(initial.id()).orElseThrow());
        assertEquals(2L, count("active_blob_reservation"));

        var archivedFirst = new DocumentVersion(first.id(), 1, first.sourceBlobId(), DocumentStatus.ARCHIVED,
                first.parseStatus(), actor, AT, actor, AT.plusSeconds(3));
        var archived = new DocumentAggregate(initial.id(), space, "Manual 2", List.of(archivedFirst, second),
                first.id(), true, 2);
        documents.replace(archived, 1);

        assertEquals(archived, documents.findById(initial.id()).orElseThrow());
        assertEquals(0L, count("active_blob_reservation"));
        assertEquals(2L, count("document_version"));
        assertTrue(documents.findActiveVersionBySpaceAndSourceBlob(space, blob('a')).isEmpty());
        assertTrue(documents.findActiveVersionBySpaceAndSourceBlob(space, blob('b')).isEmpty());
    }

    @Test
    void malformedObjectKeyFailsBeforeAnyWrite() {
        var space = createSpace();
        for (String invalid : List.of("report.pdf", "sha256/ab/" + "a".repeat(64),
                "sha256/AA/" + "A".repeat(64), "sha256/aa/" + "a".repeat(63))) {
            var bad = new DocumentAggregate(UUID.randomUUID(), space, "Bad",
                    List.of(new DocumentVersion(UUID.randomUUID(), 1, invalid, DocumentStatus.DRAFT,
                            ParseStatus.PENDING, actor, AT, null, null)), null, false, 0);
            assertThrows(IllegalArgumentException.class, () -> documents.insert(bad));
            assertEquals(0L, count("document"));
            assertEquals(0L, count("document_version"));
            assertEquals(0L, count("active_blob_reservation"));
        }
    }

    @Test
    void staleRevisionCannotChangeDocumentOrVersions() {
        var space = createSpace();
        var initial = draft(space, 'a');
        documents.insert(initial);
        var changed = new DocumentAggregate(initial.id(), space, "Changed", initial.versions(), null, false, 1);
        documents.replace(changed, 0);
        var stale = new DocumentAggregate(initial.id(), space, "Stale", initial.versions(), null, false, 1);
        var failure = assertThrows(ConcurrentDocumentUpdateException.class, () -> documents.replace(stale, 0));
        assertNull(failure.getCause());
        assertEquals(changed, documents.findById(initial.id()).orElseThrow());
        assertEquals(1L, count("document_version"));
        assertEquals(1L, count("active_blob_reservation"));
    }

    @Test
    void omittingPersistedHistoryCannotDropItsReservation() {
        var space = createSpace();
        var first = version(1, 'a', DocumentStatus.PUBLISHED, ParseStatus.SUCCEEDED,
                actor, AT, actor, AT);
        var second = version(2, 'b', DocumentStatus.DRAFT, ParseStatus.PENDING,
                actor, AT.plusSeconds(1), actor, AT.plusSeconds(1));
        var original = new DocumentAggregate(UUID.randomUUID(), space, "Original", List.of(first, second),
                first.id(), false, 0);
        documents.insert(original);
        var missingHistory = new DocumentAggregate(original.id(), space, "Incomplete", List.of(first),
                first.id(), false, 1);

        assertRejectedWithoutChanges(original, List.of("a".repeat(64), "b".repeat(64)),
                () -> documents.replace(missingHistory, 0));
        assertEquals(new DocumentVersionRef(original.id(), second.id()),
                documents.findActiveVersionBySpaceAndSourceBlob(space, blob('b')).orElseThrow());
    }

    @Test
    void historicalIdentityAndImmutableFieldsCannotBeRewritten() {
        var space = createSpace();
        var original = draft(space, 'a');
        documents.insert(original);
        var first = original.versions().getFirst();
        UUID otherActor = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :externalId, 'Other')")
                .param("id", otherActor).param("externalId", otherActor.toString()).update();
        var changedHistory = List.of(
                new DocumentVersion(UUID.randomUUID(), 1, first.sourceBlobId(), first.status(),
                        first.parseStatus(), actor, AT, actor, AT),
                new DocumentVersion(first.id(), 2, first.sourceBlobId(), first.status(),
                        first.parseStatus(), actor, AT, actor, AT),
                new DocumentVersion(first.id(), 1, blob('c'), first.status(),
                        first.parseStatus(), actor, AT, actor, AT),
                new DocumentVersion(first.id(), 1, first.sourceBlobId(), first.status(),
                        first.parseStatus(), otherActor, AT, actor, AT),
                new DocumentVersion(first.id(), 1, first.sourceBlobId(), first.status(),
                        first.parseStatus(), actor, AT.plusSeconds(1), actor, AT));

        for (var changed : changedHistory) {
            var replacement = new DocumentAggregate(original.id(), space, "Changed", List.of(changed),
                    null, false, 1);
            assertRejectedWithoutChanges(original, List.of("a".repeat(64)),
                    () -> documents.replace(replacement, 0));
        }
    }

    @Test
    void existingVersionCannotBeMovedFromAnotherDocument() {
        var space = createSpace();
        var target = draft(space, 'a');
        documents.insert(target);
        var donorFirst = version(1, 'b', DocumentStatus.DRAFT, ParseStatus.PENDING, actor, AT, actor, AT);
        var donorSecond = version(2, 'c', DocumentStatus.DRAFT, ParseStatus.PENDING,
                actor, AT.plusSeconds(1), actor, AT.plusSeconds(1));
        var donor = new DocumentAggregate(UUID.randomUUID(), space, "Donor", List.of(donorFirst, donorSecond),
                null, false, 0);
        documents.insert(donor);
        var moved = new DocumentAggregate(target.id(), space, "Moved", List.of(target.latestVersion(), donorSecond),
                null, false, 1);

        assertRejectedWithoutChanges(target, List.of("a".repeat(64)),
                () -> documents.replace(moved, 0));
        assertEquals(donor, documents.findById(donor.id()).orElseThrow());
        assertEquals(new DocumentVersionRef(donor.id(), donorSecond.id()),
                documents.findActiveVersionBySpaceAndSourceBlob(space, blob('c')).orElseThrow());
    }

    @Test
    void revisionMustAdvanceExactlyOnce() {
        var space = createSpace();
        var original = draft(space, 'a');
        documents.insert(original);
        for (long invalidRevision : List.of(0L, 2L)) {
            var replacement = new DocumentAggregate(original.id(), space, "Changed", original.versions(),
                    null, false, invalidRevision);
            assertRejectedWithoutChanges(original, List.of("a".repeat(64)),
                    () -> documents.replace(replacement, 0));
        }
    }

    @Test
    void revisionAtLongMaximumCannotWrap() {
        var space = createSpace();
        var draft = draft(space, 'a');
        var original = new DocumentAggregate(draft.id(), space, draft.title(), draft.versions(),
                null, false, Long.MAX_VALUE);
        documents.insert(original);
        var wrapped = new DocumentAggregate(original.id(), space, "Wrapped", original.versions(),
                null, false, Long.MIN_VALUE);

        assertRejectedWithoutChanges(original, List.of("a".repeat(64)),
                () -> documents.replace(wrapped, Long.MAX_VALUE));
    }

    @Test
    void concurrentCreatesForSameSpaceAndHashHaveOneWinner() throws Exception {
        var space = createSpace();
        var first = draft(space, 'a');
        var second = draft(space, 'a');
        var result = race(() -> documents.insert(first), () -> documents.insert(second));

        assertEquals(1, result.successes());
        assertEquals(1, result.failures().size());
        var failure = assertInstanceOf(PostgresPersistenceExceptionTranslator.Failure.class,
                result.failures().getFirst());
        assertEquals("UNIQUE_CONSTRAINT", failure.code());
        assertEquals("UNIQUE_CONSTRAINT", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(1L, count("document"));
        assertEquals(1L, count("document_version"));
        assertEquals(1L, count("active_blob_reservation"));
    }

    @Test
    void concurrentReplacementsAtSameRevisionHaveOneWinner() throws Exception {
        var space = createSpace();
        var initial = draft(space, 'a');
        documents.insert(initial);
        var left = new DocumentAggregate(initial.id(), space, "Left", initial.versions(), null, false, 1);
        var right = new DocumentAggregate(initial.id(), space, "Right", initial.versions(), null, false, 1);
        var result = race(() -> documents.replace(left, 0), () -> documents.replace(right, 0));

        assertEquals(1, result.successes());
        assertEquals(1, result.failures().size());
        assertInstanceOf(ConcurrentDocumentUpdateException.class, result.failures().getFirst());
        assertEquals(1, documents.findById(initial.id()).orElseThrow().revision());
        assertEquals(1L, count("active_blob_reservation"));
    }

    private void assertRejectedWithoutChanges(DocumentAggregate original, List<String> hashes, Runnable attempt) {
        var failure = assertThrows(ConcurrentDocumentUpdateException.class, attempt::run);
        assertNull(failure.getCause());
        assertEquals(original, documents.findById(original.id()).orElseThrow());
        assertEquals(hashes, jdbc.sql("SELECT sha256 FROM active_blob_reservation WHERE document_id = :documentId ORDER BY sha256")
                .param("documentId", original.id()).query(String.class).list());
        assertEquals(original.versions().size(), jdbc.sql("SELECT count(*) FROM document_version WHERE document_id = :documentId")
                .param("documentId", original.id()).query(Long.class).single());
    }
}

abstract class DocumentPostgresFixture {
    static final Instant AT = Instant.parse("2026-09-29T08:07:06.123456Z");
    static final UUID FIXED_AUDIT_ID = UUID.fromString("01995f10-0010-7000-8000-000000000001");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_document_test").withUsername("migration_owner").withPassword("migration_test_only");
    private static DataSource runtimeDataSource;
    private static AnnotationConfigApplicationContext context;
    static JdbcClient jdbc;
    static JdbcClient ownerJdbc;
    static JdbcKnowledgeSpaceRepository spaces;
    static JdbcDocumentRepository documents;
    static DocumentService service;
    UUID actor;

    @BeforeAll
    static void startDatabase() throws SQLException {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_document_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        runtimeDataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only");
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = context.getBean(JdbcClient.class);
        ownerJdbc = JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        spaces = context.getBean(JdbcKnowledgeSpaceRepository.class);
        documents = context.getBean(JdbcDocumentRepository.class);
        service = context.getBean(DocumentService.class);
    }

    @BeforeEach
    void createActor() {
        actor = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :externalId, 'Tester')")
                .param("id", actor).param("externalId", actor.toString()).update();
    }

    @AfterEach
    void clearDatabase() {
        ownerJdbc.sql("DELETE FROM audit_event").update();
        jdbc.sql("DELETE FROM active_blob_reservation").update();
        jdbc.sql("UPDATE document SET current_published_version_id = NULL").update();
        jdbc.sql("DELETE FROM document_version").update();
        jdbc.sql("DELETE FROM document").update();
        jdbc.sql("DELETE FROM knowledge_space").update();
        jdbc.sql("DELETE FROM app_user").update();
    }

    @AfterAll
    static void stopDatabase() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    UUID createSpace() {
        UUID id = UUID.randomUUID();
        spaces.insert(new KnowledgeSpace(id, "Engineering", actor, AT));
        return id;
    }

    DocumentAggregate draft(UUID space, char digest) {
        return new DocumentAggregate(UUID.randomUUID(), space, "Draft",
                List.of(version(1, digest, DocumentStatus.DRAFT, ParseStatus.PENDING, actor, AT, actor, AT)),
                null, false, 0);
    }

    static DocumentVersion version(int number, char digest, DocumentStatus status, ParseStatus parse,
                                   UUID createdBy, Instant createdAt, UUID changedBy, Instant changedAt) {
        return new DocumentVersion(UUID.randomUUID(), number, blob(digest), status, parse,
                createdBy, createdAt, changedBy, changedAt);
    }

    static String blob(char digest) {
        String hash = String.valueOf(digest).repeat(64);
        return "sha256/" + hash.substring(0, 2) + "/" + hash;
    }

    static long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    static RaceResult race(Runnable left, Runnable right) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Throwable> first = executor.submit(() -> runRacer(left, ready, start));
            Future<Throwable> second = executor.submit(() -> runRacer(right, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            var failures = java.util.stream.Stream.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))
                    .filter(java.util.Objects::nonNull).toList();
            return new RaceResult(2 - failures.size(), failures);
        }
    }

    private static Throwable runRacer(Runnable action, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    record RaceResult(int successes, List<Throwable> failures) { }

    @TestConfiguration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfig {
        @Bean DataSource dataSource() { return runtimeDataSource; }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean JdbcClient jdbcClient(DataSource dataSource) { return JdbcClient.create(dataSource); }
        @Bean JdbcKnowledgeSpaceRepository spaces(JdbcClient jdbc) { return new JdbcKnowledgeSpaceRepository(jdbc); }
        @Bean JdbcDocumentRepository documents(JdbcClient jdbc) { return new JdbcDocumentRepository(jdbc); }
        @Bean JdbcAuditEventStore auditStore(JdbcClient jdbc) { return new JdbcAuditEventStore(jdbc); }
        @Bean DocumentService documentService(JdbcDocumentRepository documents, JdbcAuditEventStore auditStore) {
            AuditSink fixedIdRealAudit = command -> {
                var event = new AuditEvent(FIXED_AUDIT_ID, command.actorId(), command.action(),
                        command.resourceType(), command.resourceId(), AuditOutcome.SUCCEEDED,
                        command.metadata(), AT);
                auditStore.append(event);
                return event;
            };
            var access = new AccessDecisionService(new EffectivePrincipalSet(userId -> {
                throw new UnsupportedOperationException();
            }), (spaceId, documentId, principals, at) -> java.util.Optional.empty());
            return new DocumentService(documents, access, DocumentContentGateway.unavailable(),
                    (userId, spaceId, at) -> List.of(), KnowledgeSpaceReadAuthorization.denyAll(),
                    KnowledgeWriteAuthorization.allowAll(), fixedIdRealAudit);
        }
    }
}
