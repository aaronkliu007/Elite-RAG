package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.access.AccessDecisionService;
import com.yunjihai.rag.access.AclService;
import com.yunjihai.rag.access.DocumentSpaceLookup;
import com.yunjihai.rag.access.EffectivePrincipalSet;
import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.access.PrincipalType;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditEvent;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.knowledge.AuthorizedDocumentQuery;
import com.yunjihai.rag.knowledge.DocumentContentGateway;
import com.yunjihai.rag.knowledge.DocumentRepository;
import com.yunjihai.rag.knowledge.DocumentService;
import com.yunjihai.rag.knowledge.DocumentStatus;
import com.yunjihai.rag.knowledge.KnowledgeSpaceReadAuthorization;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import com.yunjihai.rag.retrieval.projection.ProjectionChangePublisher;
import com.yunjihai.rag.retrieval.projection.ProjectionCutoverLock;
import com.yunjihai.rag.retrieval.projection.ProjectionDocumentLock;
import com.yunjihai.rag.retrieval.projection.ProjectionEventRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionEventTransactionIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-30T09:00:00Z");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_projection_transaction_test")
            .withUsername("migration_owner")
            .withPassword("migration_test_only");

    private static DataSource dataSource;
    private static AnnotationConfigApplicationContext context;
    private static JdbcClient jdbc;
    private static DocumentService documents;
    private static AclService acl;
    private static ToggleAuditSink audit;
    private static ProjectionDocumentLock documentLock;
    private static PlatformTransactionManager transactions;

    private UUID actorId;
    private UUID spaceId;

    @BeforeAll
    static void startDatabase() throws Exception {
        POSTGRES.start();
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_projection_transaction_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only");
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = context.getBean(JdbcClient.class);
        documents = context.getBean(DocumentService.class);
        acl = context.getBean(AclService.class);
        audit = context.getBean(ToggleAuditSink.class);
        documentLock = context.getBean(ProjectionDocumentLock.class);
        transactions = context.getBean(PlatformTransactionManager.class);
    }

    @AfterAll
    static void stopDatabase() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @BeforeEach
    void seedScope() {
        audit.fail.set(false);
        jdbc.sql("DELETE FROM retrieval_projection_event").update();
        jdbc.sql("DELETE FROM acl_entry").update();
        jdbc.sql("DELETE FROM active_blob_reservation").update();
        jdbc.sql("UPDATE document SET current_published_version_id = NULL").update();
        jdbc.sql("DELETE FROM document_version").update();
        jdbc.sql("DELETE FROM document").update();
        jdbc.sql("DELETE FROM knowledge_space").update();
        jdbc.sql("DELETE FROM app_user").update();

        actorId = UUID.randomUUID();
        spaceId = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :external, 'Actor')")
                .param("id", actorId).param("external", actorId.toString()).update();
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by) VALUES (:id, 'Space', :actor)")
                .param("id", spaceId).param("actor", actorId).update();
    }

    @Test
    void documentLifecycleAndAclChangesEmitCorrectScopes() {
        var document = documents.createDraft(spaceId, "方案", blobId(), actorId, NOW);
        var versionId = document.latestVersion().id();

        var principal = new PrincipalRef(PrincipalType.USER, actorId);
        acl.grantSpace(actorId, spaceId, principal, null, NOW.plusSeconds(1));
        acl.grantDocument(actorId, spaceId, document.id(), principal, null, NOW.plusSeconds(2));

        documents.completeParsing(document.id(), versionId, actorId, NOW.plusSeconds(3));
        documents.publish(document.id(), versionId, actorId, NOW.plusSeconds(4));
        documents.archive(document.id(), actorId, NOW.plusSeconds(5));

        var events = jdbc.sql("""
                SELECT scope_type, space_id, document_id, version_id, document_revision
                FROM retrieval_projection_event ORDER BY sequence
                """).query((row, number) -> Map.of(
                        "scope", row.getString("scope_type"),
                        "space", row.getObject("space_id", UUID.class),
                        "document", row.getObject("document_id", UUID.class) == null
                                ? "none" : row.getObject("document_id", UUID.class).toString(),
                        "version", row.getObject("version_id", UUID.class) == null
                                ? "none" : row.getObject("version_id", UUID.class).toString(),
                        "revision", row.getLong("document_revision"))).list();

        assertEquals(List.of("SPACE", "DOCUMENT", "DOCUMENT", "DOCUMENT", "DOCUMENT"),
                events.stream().map(event -> (String) event.get("scope")).toList());
        assertTrue(events.stream().allMatch(event -> event.get("space").equals(spaceId)));
        assertEquals("none", events.get(1).get("version"));
        assertEquals(document.id().toString(), events.getLast().get("document"));
    }

    @Test
    void auditFailureRollsBackPublishedStateAndProjectionEvent() {
        var document = documents.createDraft(spaceId, "方案", blobId(), actorId, NOW);
        var versionId = document.latestVersion().id();
        documents.completeParsing(document.id(), versionId, actorId, NOW.plusSeconds(1));
        jdbc.sql("DELETE FROM retrieval_projection_event").update();
        audit.fail.set(true);

        assertThrows(IllegalStateException.class,
                () -> documents.publish(document.id(), versionId, actorId, NOW.plusSeconds(2)));

        var persisted = context.getBean(DocumentRepository.class).findById(document.id()).orElseThrow();
        assertTrue(persisted.currentPublishedVersionId().isEmpty());
        assertEquals(DocumentStatus.IN_REVIEW, persisted.version(versionId).status());
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM retrieval_projection_event").query(Long.class).single());
    }

    @Test
    void documentAdvisoryLockSerializesConcurrentTransactions() throws Exception {
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        UUID documentId = UUID.randomUUID();
        var transaction = new TransactionTemplate(transactions);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> transaction.executeWithoutResult(ignored ->
                    documentLock.withDocumentLock(documentId, () -> {
                        firstEntered.countDown();
                        await(releaseFirst);
                        return null;
                    })));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> {
                secondStarted.countDown();
                transaction.executeWithoutResult(ignored ->
                        documentLock.withDocumentLock(documentId, () -> {
                            secondEntered.countDown();
                            return null;
                        }));
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertFalse(secondEntered.await(250, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", failure);
        }
    }

    private static String blobId() {
        return "sha256/aa/" + "a".repeat(64);
    }

    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfig {
        @Bean DataSource dataSource() { return dataSource; }
        @Bean PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean JdbcClient jdbcClient(DataSource source) { return JdbcClient.create(source); }
        @Bean JdbcDocumentRepository documentRepository(JdbcClient client) {
            return new JdbcDocumentRepository(client);
        }
        @Bean JdbcAclCommandRepository aclRepository(JdbcClient client) {
            return new JdbcAclCommandRepository(client);
        }
        @Bean ProjectionEventRepository eventRepository(JdbcClient client) {
            return new JdbcProjectionEventRepository(client);
        }
        @Bean ProjectionDocumentLock projectionDocumentLock(JdbcClient client) {
            return new JdbcProjectionDocumentLock(client);
        }
        @Bean ProjectionCutoverLock projectionCutoverLock(JdbcClient client) {
            return new JdbcProjectionCutoverLock(client);
        }
        @Bean ProjectionChangePublisher projectionPublisher(
                JdbcClient client,
                ProjectionEventRepository events,
                ProjectionDocumentLock documentLock,
                ProjectionCutoverLock cutoverLock) {
            return new JdbcProjectionChangePublisher(client, events, documentLock, cutoverLock);
        }
        @Bean ToggleAuditSink auditSink() { return new ToggleAuditSink(); }
        @Bean DocumentService documentService(
                JdbcDocumentRepository repository,
                ProjectionChangePublisher projections,
                ToggleAuditSink auditSink) {
            var access = new AccessDecisionService(new EffectivePrincipalSet(userId -> {
                throw new UnsupportedOperationException();
            }), (spaceId, documentId, principals, at) -> java.util.Optional.empty());
            AuthorizedDocumentQuery query = (userId, spaceId, at) -> List.of();
            return new DocumentService(repository, access, DocumentContentGateway.unavailable(), query,
                    KnowledgeSpaceReadAuthorization.denyAll(), KnowledgeWriteAuthorization.allowAll(),
                    auditSink, projections);
        }
        @Bean AclService aclService(
                JdbcAclCommandRepository repository,
                JdbcClient client,
                ToggleAuditSink auditSink,
                ProjectionChangePublisher projections) {
            DocumentSpaceLookup lookup = (spaceId, documentId) -> client.sql("""
                    SELECT EXISTS (SELECT 1 FROM document WHERE id = :document AND space_id = :space)
                    """).param("document", documentId).param("space", spaceId).query(Boolean.class).single();
            return new AclService(repository, KnowledgeWriteAuthorization.allowAll(), lookup,
                    auditSink, projections);
        }
    }

    static final class ToggleAuditSink implements AuditSink {
        private final AtomicBoolean fail = new AtomicBoolean();

        @Override
        public AuditEvent record(AuditCommand command) {
            if (fail.get()) throw new IllegalStateException("AUDIT_TEST_FAILURE");
            return new AuditEvent(UUID.randomUUID(), command.actorId(), command.action(),
                    command.resourceType(), command.resourceId(), command.outcome(), command.metadata(), NOW);
        }
    }
}
