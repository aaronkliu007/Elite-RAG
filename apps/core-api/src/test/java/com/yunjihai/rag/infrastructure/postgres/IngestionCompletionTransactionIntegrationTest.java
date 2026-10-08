package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.access.AccessDecisionService;
import com.yunjihai.rag.access.EffectivePrincipalSet;
import com.yunjihai.rag.audit.AuditService;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.ingestion.CanonicalBoundingBox;
import com.yunjihai.rag.ingestion.CanonicalDocumentBody;
import com.yunjihai.rag.ingestion.CanonicalDocumentV1;
import com.yunjihai.rag.ingestion.CanonicalNode;
import com.yunjihai.rag.ingestion.CanonicalTableCell;
import com.yunjihai.rag.ingestion.IngestionJobService;
import com.yunjihai.rag.ingestion.DocumentVersionId;
import com.yunjihai.rag.ingestion.IngestionLease;
import com.yunjihai.rag.ingestion.IngestionRequest;
import com.yunjihai.rag.ingestion.IngestionLeaseConflictException;
import com.yunjihai.rag.ingestion.InvalidParserResponseException;
import com.yunjihai.rag.ingestion.ParserError;
import com.yunjihai.rag.ingestion.ParserErrorCode;
import com.yunjihai.rag.ingestion.ParserStatus;
import com.yunjihai.rag.knowledge.DocumentLifecycleException;
import com.yunjihai.rag.knowledge.ConcurrentDocumentUpdateException;
import com.yunjihai.rag.knowledge.DocumentContentGateway;
import com.yunjihai.rag.knowledge.DocumentMetadata;
import com.yunjihai.rag.knowledge.DocumentService;
import com.yunjihai.rag.knowledge.KnowledgeSpaceReadAuthorization;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import com.yunjihai.rag.retrieval.projection.ProjectionChangePublisher;
import com.yunjihai.rag.retrieval.projection.ProjectionCutoverLock;
import com.yunjihai.rag.retrieval.projection.ProjectionDocumentLock;
import com.yunjihai.rag.retrieval.projection.ProjectionEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class IngestionCompletionTransactionIntegrationTest extends IngestionPostgresFixture {
    @Test
    void acceptsExactlyOneMillionNodesAndRejectsOnlyTheNextCount() {
        assertDoesNotThrow(() -> JdbcParsedDocumentStore.validateNodeCount(1_000_000));
        assertThrows(InvalidParserResponseException.class,
                () -> JdbcParsedDocumentStore.validateNodeCount(1_000_001));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void persistsFiftyThousandNodeDeepChainWithStableOrderUsingBoundedBatching() {
        int size = 50_000;
        var nodes = IntStream.range(0, size)
                .mapToObj(index -> new CanonicalNode(
                        "node-" + index,
                        "PARAGRAPH",
                        "text-" + index,
                        index == 0 ? null : "node-" + (index - 1),
                        1,
                        null,
                        null,
                        1.0))
                .toList();

        parsed.replace(request.documentVersion(), body(nodes), AT);

        assertEquals(size, count("canonical_node"));
        assertEquals(0, ownerJdbc.sql("SELECT min(stable_order) FROM canonical_node WHERE version_id = :versionId")
                .param("versionId", request.documentVersion().versionId()).query(Integer.class).single());
        assertEquals(size - 1, ownerJdbc.sql("SELECT max(stable_order) FROM canonical_node WHERE version_id = :versionId")
                .param("versionId", request.documentVersion().versionId()).query(Integer.class).single());
        assertEquals("node-49999", ownerJdbc.sql("""
                SELECT node_id FROM canonical_node WHERE version_id = :versionId ORDER BY stable_order DESC LIMIT 1
                """).param("versionId", request.documentVersion().versionId()).query(String.class).single());
    }

    @Test
    void canonicalReplacementIsIdempotentAndPreservesStableOrderAndJsonb() {
        var body = validBody();
        parsed.replace(request.documentVersion(), body, AT);
        parsed.replace(request.documentVersion(), body, AT.plusSeconds(1));

        assertEquals(1L, count("canonical_document"));
        assertEquals(List.of("root", "table"), ownerJdbc.sql("""
                SELECT node_id FROM canonical_node WHERE version_id = :versionId ORDER BY stable_order
                """).param("versionId", request.documentVersion().versionId()).query(String.class).list());
        assertEquals("{\"x\":0.1,\"y\":0.2,\"width\":0.3,\"height\":0.4}",
                json("bounding_box", "root").replace(" ", ""));
        assertTrue(json("table_cells", "table").contains("单元格"));
    }

    @Test
    void invalidCanonicalShapeIsRejectedBeforeFirstWrite() {
        var root = validBody().nodes().getFirst();
        var invalid = List.of(
                new CanonicalDocumentBody("manual.pdf", "application/pdf", 1, null),
                new CanonicalDocumentBody("x".repeat(256), "application/pdf", 1, List.of()),
                body(Collections.nCopies(1_000_001, root)),
                body(List.of(root, root)),
                body(List.of(new CanonicalNode("null-kind", null, "x", null, 1, null, null, 1.0))),
                body(List.of(new CanonicalNode("bad-kind", "UNKNOWN", "x", null, 1, null, null, 1.0))),
                body(List.of(new CanonicalNode("x".repeat(129), "PARAGRAPH", "x", null, 1, null, null, 1.0))),
                body(List.of(new CanonicalNode("child", "PARAGRAPH", "x", "missing", 1, null, null, 1.0))),
                body(List.of(
                        new CanonicalNode("a", "PARAGRAPH", "a", "b", 1, null, null, 1.0),
                        new CanonicalNode("b", "PARAGRAPH", "b", "a", 1, null, null, 1.0))),
                body(List.of(new CanonicalNode("bad-page", "PARAGRAPH", "x", null, 2, null, null, 1.0))),
                body(List.of(new CanonicalNode("bad-box", "PARAGRAPH", "x", null, 1,
                        new CanonicalBoundingBox(Double.NaN, 0, 1, 1), null, 1.0))),
                body(List.of(new CanonicalNode("negative-box", "PARAGRAPH", "x", null, 1,
                        new CanonicalBoundingBox(-0.1, 0, 1, 1), null, 1.0))),
                body(List.of(new CanonicalNode("bad-table", "TABLE", "x", null, 1, null,
                        List.of(new CanonicalTableCell(0, 0, 0, 1, "x")), 1.0))),
                body(List.of(new CanonicalNode("duplicate-cells", "TABLE", "x", null, 1, null,
                        List.of(new CanonicalTableCell(0, 0, 1, 1, "a"),
                                new CanonicalTableCell(0, 0, 1, 1, "b")), 1.0))),
                body(List.of(new CanonicalNode("bad-confidence", "PARAGRAPH", "x", null, 1,
                        null, null, Double.POSITIVE_INFINITY))));

        for (var body : invalid) {
            assertThrows(InvalidParserResponseException.class,
                    () -> parsed.replace(request.documentVersion(), body, AT));
            assertEquals(0L, count("canonical_document"));
            assertEquals(0L, count("canonical_node"));
        }
    }

    @Test
    void invalidReplacementCannotDeletePreviouslyPersistedCanonicalContent() {
        parsed.replace(request.documentVersion(), validBody(), AT);

        assertThrows(InvalidParserResponseException.class, () -> parsed.replace(
                request.documentVersion(),
                body(List.of(new CanonicalNode("child", "PARAGRAPH", "x", "missing", 1, null, null, 1.0))),
                AT.plusSeconds(1)));

        assertEquals(1L, count("canonical_document"));
        assertEquals(List.of("root", "table"), ownerJdbc.sql("""
                SELECT node_id FROM canonical_node WHERE version_id = :versionId ORDER BY stable_order
                """).param("versionId", request.documentVersion().versionId()).query(String.class).list());
    }

    @Test
    void successfulCompletionAtomicallyPersistsCanonicalDraftJobAndAuditState() {
        UUID jobId = UUID.randomUUID();
        jobs.enqueue(jobId, request, AT);
        var lease = jobs.claimNext("worker-a", AT, AT.plusSeconds(30)).orElseThrow();

        try (var context = serviceContext()) {
            context.getBean(IngestionJobService.class).complete(lease, result(jobId));
        }

        assertEquals(1L, count("canonical_document"));
        assertEquals(2L, count("canonical_node"));
        assertEquals("DRAFT", documentField("status"));
        assertEquals("SUCCEEDED", documentField("parse_status"));
        assertEquals(1L, documentRevision());
        assertEquals("SUCCEEDED", jobField(jobId, "status"));
        assertEquals(1L, count("audit_event"));
    }

    @Test
    void duplicateWorkerCompletionIsAnEndToEndNoOp() {
        UUID jobId = UUID.randomUUID();
        jobs.enqueue(jobId, request, AT);
        var lease = jobs.claimNext("worker-a", AT, AT.plusSeconds(30)).orElseThrow();
        try (var context = serviceContext()) {
            var service = context.getBean(IngestionJobService.class);
            var repository = context.getBean(JdbcDocumentRepository.class);
            service.complete(lease, result(jobId));
            var before = repository.findById(document.id()).orElseThrow();
            var canonicalBefore = ownerJdbc.sql("SELECT body_text FROM canonical_node ORDER BY stable_order")
                    .query(String.class).list();

            assertDoesNotThrow(() -> service.complete(lease, result(jobId)));

            assertEquals(before, repository.findById(document.id()).orElseThrow());
            assertEquals(canonicalBefore, ownerJdbc.sql(
                    "SELECT body_text FROM canonical_node ORDER BY stable_order").query(String.class).list());
            assertEquals(1L, count("canonical_document"));
            assertEquals(1L, count("audit_event"));
            assertEquals(1L, count("retrieval_projection_event"));
        }
    }

    @Test
    void lateCompletionForSupersededVersionCannotRewriteLatestDraft() {
        UUID jobId = UUID.randomUUID();
        jobs.enqueue(jobId, request, AT);
        var lease = jobs.claimNext("worker-a", AT, AT.plusSeconds(30)).orElseThrow();
        try (var context = serviceContext()) {
            var service = context.getBean(IngestionJobService.class);
            var documentService = context.getBean(DocumentService.class);
            var repository = context.getBean(JdbcDocumentRepository.class);
            documentService.createNextDraft(
                    document.id(), "sha256/bb/" + "b".repeat(64), actor, AT.plusSeconds(1));
            var before = repository.findById(document.id()).orElseThrow();

            assertDoesNotThrow(() -> service.complete(lease, result(jobId)));

            assertEquals(before, repository.findById(document.id()).orElseThrow());
            assertEquals(0L, count("canonical_document"));
            assertEquals("SUCCEEDED", jobField(jobId, "status"));
            assertEquals(1L, count("audit_event"));
        }
    }

    @Test
    void lateCompletionAfterArchiveCannotRewriteHistory() {
        UUID jobId = UUID.randomUUID();
        jobs.enqueue(jobId, request, AT);
        var lease = jobs.claimNext("worker-a", AT, AT.plusSeconds(30)).orElseThrow();
        try (var context = serviceContext()) {
            var service = context.getBean(IngestionJobService.class);
            var documentService = context.getBean(DocumentService.class);
            var repository = context.getBean(JdbcDocumentRepository.class);
            var versionId = request.documentVersion().versionId();
            documentService.updateMetadata(document.id(), new DocumentMetadata("制度", List.of()),
                    actor, AT.plusSeconds(1));
            documentService.completeParsing(document.id(), versionId, actor, AT.plusSeconds(2));
            documentService.submitForReview(document.id(), versionId, actor, AT.plusSeconds(3));
            documentService.publish(document.id(), versionId, actor, AT.plusSeconds(4));
            documentService.archive(document.id(), actor, AT.plusSeconds(5));
            var before = repository.findById(document.id()).orElseThrow();
            long projectionEventsBefore = count("retrieval_projection_event");

            assertDoesNotThrow(() -> service.complete(lease, result(jobId)));

            assertEquals(before, repository.findById(document.id()).orElseThrow());
            assertEquals(0L, count("canonical_document"));
            assertEquals(projectionEventsBefore, count("retrieval_projection_event"));
            assertEquals("SUCCEEDED", jobField(jobId, "status"));
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void successfulCompletionUsesDocumentBeforeVersionRowLockOrder() throws Exception {
        UUID jobId = UUID.randomUUID();
        jobs.enqueue(jobId, request, AT);
        var lease = jobs.claimNext("worker-a", AT, AT.plusSeconds(30)).orElseThrow();
        try (var context = serviceContext();
             var blocker = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only");
             var executor = Executors.newFixedThreadPool(2)) {
            var documents = context.getBean(DocumentService.class);
            var ingestion = context.getBean(IngestionJobService.class);
            documents.updateMetadata(
                    document.id(), new DocumentMetadata("初始分类", List.of()), actor, AT);
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement(
                    "SELECT id FROM document_version WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, request.documentVersion().versionId());
                statement.executeQuery();
            }

            var completion = executor.submit(() -> {
                ingestion.complete(lease, result(jobId));
                return null;
            });
            Thread.sleep(250);
            var metadataMutation = executor.submit(() -> {
                try {
                    documents.updateMetadata(
                            document.id(), new DocumentMetadata("并发分类", List.of()),
                            actor, AT.plusSeconds(1));
                    return (RuntimeException) null;
                } catch (RuntimeException failure) {
                    return failure;
                }
            });
            Thread.sleep(250);
            blocker.rollback();

            assertNull(completion.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ConcurrentDocumentUpdateException.class,
                    metadataMutation.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void successAndTerminalFailureSerializeOnJobBeforeDocumentRows() throws Exception {
        UUID jobId = UUID.randomUUID();
        jobs.enqueue(jobId, request, AT);
        var lease = jobs.claimNext("worker-a", AT, AT.plusSeconds(30)).orElseThrow();
        try (var context = serviceContext();
             var blocker = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only");
             var executor = Executors.newFixedThreadPool(2)) {
            var ingestion = context.getBean(IngestionJobService.class);
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement(
                    "SELECT id FROM ingestion_job WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, jobId);
                statement.executeQuery();
            }

            var completion = executor.submit(() -> {
                ingestion.complete(lease, result(jobId));
                return null;
            });
            Thread.sleep(250);
            var terminalFailure = executor.submit(() -> {
                try {
                    ingestion.fail(lease, terminalError());
                    return (RuntimeException) null;
                } catch (RuntimeException failure) {
                    return failure;
                }
            });
            Thread.sleep(250);
            blocker.rollback();

            assertNull(completion.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IngestionLeaseConflictException.class,
                    terminalFailure.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failuresAfterEveryCompletionWriteStageRollBackTheWholeTransaction() {
        for (String stage : List.of("canonical_document", "canonical_node", "document_version", "ingestion_job", "audit_event")) {
            UUID jobId = UUID.randomUUID();
            jobs.enqueue(jobId, request, AT);
            var lease = jobs.claimNext("worker-a", AT, AT.plusSeconds(30)).orElseThrow();
            installFailpoint(stage);

            try (var context = serviceContext()) {
                var service = context.getBean(IngestionJobService.class);
                assertThrows(RuntimeException.class, () -> service.complete(lease, result(jobId)));
            }

            assertEquals(0L, count("canonical_document"), stage);
            assertEquals(0L, count("canonical_node"), stage);
            assertEquals("DRAFT", documentField("status"), stage);
            assertEquals("PENDING", documentField("parse_status"), stage);
            assertEquals(0L, documentRevision(), stage);
            assertEquals("LEASED", jobField(jobId, "status"), stage);
            assertEquals(lease.leaseToken(), jobToken(jobId), stage);
            assertEquals(0L, count("audit_event"), stage);

            removeFailpoint(stage);
            jobs.complete(jobId, lease.leaseToken(), AT.plusSeconds(1));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUPERSEDED", "PUBLISHED", "ARCHIVED"})
    void exhaustedObsoleteJobsTerminateWithoutChangingDocumentsAndReleaseTheQueue(String state) {
        try (var context = serviceContext()) {
            var service = context.getBean(IngestionJobService.class);
            var documents = context.getBean(JdbcDocumentRepository.class);
            var successor = prepareObsoleteVersion(context.getBean(DocumentService.class), state);
            var before = documents.findById(document.id()).orElseThrow();
            var nextBefore = documents.findById(successor.documentVersion().documentId()).orElseThrow();
            UUID oldJob = UUID.randomUUID();
            var expired = exhaustLease(oldJob);
            UUID nextJob = UUID.randomUUID();
            jobs.enqueue(nextJob, successor, AT);

            assertTrue(assertDoesNotThrow(() -> service.claimNext("recovery-worker")).isEmpty());

            assertTerminated(oldJob, "TIMEOUT", 4);
            assertEquals(before, documents.findById(document.id()).orElseThrow());
            assertEquals(nextBefore, documents.findById(successor.documentVersion().documentId()).orElseThrow());
            var stale = new IngestionLease(oldJob, expired.leaseToken(), expired.attempt(),
                    expired.workerId(), AT.plusSeconds(30), expired.request());
            assertThrows(IngestionLeaseConflictException.class, () -> service.fail(stale, terminalError()));
            assertEquals(1L, count("audit_event"));
            var next = service.claimNext("next-worker").orElseThrow();
            assertEquals(nextJob, next.jobId());
            assertEquals(1, next.attempt());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUPERSEDED", "PUBLISHED", "ARCHIVED"})
    void terminalParserFailureOfObsoleteVersionPreservesLifecycleAndAudit(String state) {
        try (var context = serviceContext()) {
            var service = context.getBean(IngestionJobService.class);
            var successor = prepareObsoleteVersion(context.getBean(DocumentService.class), state);
            var documents = context.getBean(JdbcDocumentRepository.class);
            var before = documents.findById(document.id()).orElseThrow();
            UUID oldJob = UUID.randomUUID();
            jobs.enqueue(oldJob, request, AT.minusSeconds(1));
            var lease = jobs.claimNext("worker-a", AT, AT.plusSeconds(30)).orElseThrow();
            UUID nextJob = UUID.randomUUID();
            jobs.enqueue(nextJob, successor, AT);

            assertDoesNotThrow(() -> service.fail(lease, terminalError()));

            assertTerminated(oldJob, "CONVERSION_FAILED", 1);
            assertEquals(before, documents.findById(document.id()).orElseThrow());
            assertEquals(nextJob, service.claimNext("next-worker").orElseThrow().jobId());
        }
    }

    @Test
    void obsoleteJobTerminationRollsBackClaimAndTerminalStateIfAuditFails() {
        try (var context = serviceContext()) {
            var service = context.getBean(IngestionJobService.class);
            var successor = prepareObsoleteVersion(context.getBean(DocumentService.class), "SUPERSEDED");
            var documents = context.getBean(JdbcDocumentRepository.class);
            var before = documents.findById(document.id()).orElseThrow();
            UUID oldJob = UUID.randomUUID();
            var expired = exhaustLease(oldJob);
            UUID nextJob = UUID.randomUUID();
            jobs.enqueue(nextJob, successor, AT);
            installFailpoint("audit_event");

            var failure = assertThrows(PostgresPersistenceExceptionTranslator.Failure.class,
                    () -> service.claimNext("recovery-worker"));

            assertEquals("DATABASE_FAILURE", failure.getMessage());
            assertNull(failure.getCause());
            assertEquals("LEASED", jobField(oldJob, "status"));
            assertEquals(expired.leaseToken(), jobToken(oldJob));
            assertEquals(3, attempts(oldJob));
            assertEquals("QUEUED", jobField(nextJob, "status"));
            assertEquals(0, attempts(nextJob));
            assertEquals(before, documents.findById(document.id()).orElseThrow());
            assertEquals(0L, count("audit_event"));
            removeFailpoint("audit_event");
            assertTrue(service.claimNext("recovery-worker").isEmpty());
            assertTerminated(oldJob, "TIMEOUT", 4);
            assertEquals(nextJob, service.claimNext("next-worker").orElseThrow().jobId());
        }
    }

    @Test
    void terminalFailureStillMarksCurrentDraftFailedAndAuditFailureRollsEverythingBack() {
        try (var context = serviceContext()) {
            var service = context.getBean(IngestionJobService.class);
            UUID jobId = UUID.randomUUID();
            jobs.enqueue(jobId, request, AT);
            var lease = jobs.claimNext("worker", AT, AT.plusSeconds(30)).orElseThrow();
            installFailpoint("audit_event");
            assertThrows(PostgresPersistenceExceptionTranslator.Failure.class, () -> service.fail(lease, terminalError()));
            assertEquals("PENDING", documentField("parse_status"));
            assertEquals(0L, documentRevision());
            assertEquals("LEASED", jobField(jobId, "status"));
            assertEquals(lease.leaseToken(), jobToken(jobId));
            assertEquals(0L, count("audit_event"));
            removeFailpoint("audit_event");

            service.fail(lease, terminalError());

            assertEquals("DRAFT", documentField("status"));
            assertEquals("FAILED", documentField("parse_status"));
            assertEquals(1L, documentRevision());
            assertTerminated(jobId, "CONVERSION_FAILED", 1);
        }
    }

    @Test
    void missingVersionAndCurrentReviewInconsistencyStillFailClosed() {
        try (var context = serviceContext()) {
            var documents = context.getBean(DocumentService.class);
            var service = context.getBean(IngestionJobService.class);
            documents.updateMetadata(document.id(), new DocumentMetadata("制度", List.of()), actor, AT);
            documents.completeParsing(document.id(), request.documentVersion().versionId(), actor, AT);
            documents.submitForReview(document.id(), request.documentVersion().versionId(), actor, AT);
            UUID jobId = UUID.randomUUID();
            jobs.enqueue(jobId, request, AT);
            var lease = jobs.claimNext("worker", AT, AT.plusSeconds(30)).orElseThrow();
            assertThrows(DocumentLifecycleException.class, () -> service.fail(lease, terminalError()));

            documents.publish(document.id(), request.documentVersion().versionId(), actor, AT);
            var wrongRequest = new IngestionRequest(new DocumentVersionId(document.id(), UUID.randomUUID()),
                    actor, request.sourceBlobId(), request.filename(), request.mediaType(), request.sizeBytes(), request.sha256());
            var invalid = new IngestionLease(jobId, lease.leaseToken(), 1, "worker", lease.leaseUntil(), wrongRequest);
            assertThrows(DocumentLifecycleException.class, () -> service.fail(invalid, terminalError()));

            assertEquals("LEASED", jobField(jobId, "status"));
            assertEquals(lease.leaseToken(), jobToken(jobId));
            assertEquals("PUBLISHED", documentField("status"));
            assertEquals("SUCCEEDED", documentField("parse_status"));
            assertEquals(0L, count("audit_event"));
        }
    }

    private IngestionRequest prepareObsoleteVersion(DocumentService documents, String state) {
        String sha = "b".repeat(64);
        String blob = "sha256/bb/" + sha;
        com.yunjihai.rag.knowledge.DocumentAggregate next;
        if (state.equals("SUPERSEDED")) {
            next = documents.createNextDraft(document.id(), blob, actor, AT);
        } else {
            documents.updateMetadata(document.id(), new DocumentMetadata("制度", List.of()), actor, AT);
            documents.completeParsing(document.id(), request.documentVersion().versionId(), actor, AT);
            documents.submitForReview(document.id(), request.documentVersion().versionId(), actor, AT);
            documents.publish(document.id(), request.documentVersion().versionId(), actor, AT);
            if (state.equals("ARCHIVED")) documents.archive(document.id(), actor, AT);
            next = documents.createDraft(space, "后续文档", blob, actor, AT);
        }
        return new IngestionRequest(new DocumentVersionId(next.id(), next.latestVersion().id()),
                actor, blob, "next.pdf", "application/pdf", 1234, sha);
    }

    private IngestionLease exhaustLease(UUID jobId) {
        jobs.enqueue(jobId, request, AT.minusSeconds(120));
        jobs.claimNext("expired-1", AT.minusSeconds(90), AT.minusSeconds(60)).orElseThrow();
        jobs.claimNext("expired-2", AT.minusSeconds(60), AT.minusSeconds(30)).orElseThrow();
        return jobs.claimNext("expired-3", AT.minusSeconds(30), AT).orElseThrow();
    }

    private void assertTerminated(UUID jobId, String errorCode, int attempts) {
        assertEquals("FAILED", jobField(jobId, "status"));
        assertEquals(errorCode, jobField(jobId, "error_code"));
        assertTrue(ownerJdbc.sql("SELECT lease_token IS NULL AND lease_worker IS NULL AND lease_expires_at IS NULL "
                        + "FROM ingestion_job WHERE id = :id")
                .param("id", jobId).query(Boolean.class).single());
        assertEquals(attempts, attempts(jobId));
        assertEquals(1L, count("audit_event"));
        assertEquals("PARSE", ownerJdbc.sql("SELECT action FROM audit_event").query(String.class).single());
        assertEquals("FAILED", ownerJdbc.sql("SELECT result FROM audit_event").query(String.class).single());
        assertEquals(request.documentVersion().versionId(), ownerJdbc.sql("SELECT resource_id FROM audit_event")
                .query(UUID.class).single());
        assertEquals(jobId.toString(), ownerJdbc.sql("SELECT metadata->>'jobId' FROM audit_event")
                .query(String.class).single());
        assertEquals(errorCode, ownerJdbc.sql("SELECT metadata->>'errorCode' FROM audit_event")
                .query(String.class).single());
    }

    private int attempts(UUID jobId) {
        return ownerJdbc.sql("SELECT attempts FROM ingestion_job WHERE id = :id")
                .param("id", jobId).query(Integer.class).single();
    }

    private ParserError terminalError() {
        return new ParserError(ParserErrorCode.CONVERSION_FAILED, "解析失败", false);
    }

    private AnnotationConfigApplicationContext serviceContext() {
        TestConfig.dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only");
        return new AnnotationConfigApplicationContext(TestConfig.class);
    }

    private void installFailpoint(String table) {
        ownerJdbc.sql("""
                CREATE OR REPLACE FUNCTION task6_failpoint() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'task6 failpoint'; END $$
                """).update();
        String operation = table.equals("canonical_document") || table.equals("canonical_node") || table.equals("audit_event")
                ? "INSERT" : "UPDATE";
        ownerJdbc.sql("CREATE TRIGGER task6_failpoint AFTER " + operation + " ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION task6_failpoint()").update();
    }

    private void removeFailpoint(String table) {
        ownerJdbc.sql("DROP TRIGGER task6_failpoint ON " + table).update();
        ownerJdbc.sql("DROP FUNCTION task6_failpoint()").update();
    }

    private CanonicalDocumentV1 result(UUID jobId) {
        return new CanonicalDocumentV1("canonical-document-v1", jobId, ParserStatus.SUCCEEDED, validBody(), List.of());
    }

    private CanonicalDocumentBody validBody() {
        return new CanonicalDocumentBody("manual.pdf", "application/pdf", 1, List.of(
                new CanonicalNode("root", "PARAGRAPH", "正文", null, 1,
                        new CanonicalBoundingBox(0.1, 0.2, 0.3, 0.4), null, 0.99),
                new CanonicalNode("table", "TABLE", "表格", "root", 1, null,
                        List.of(new CanonicalTableCell(0, 0, 1, 1, "单元格")), 0.98)));
    }

    private CanonicalDocumentBody body(List<CanonicalNode> nodes) {
        return new CanonicalDocumentBody("manual.pdf", "application/pdf", 1, nodes);
    }

    private long count(String table) {
        return ownerJdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private String json(String column, String nodeId) {
        return ownerJdbc.sql("SELECT " + column + "::text FROM canonical_node WHERE version_id = :versionId AND node_id = :nodeId")
                .param("versionId", request.documentVersion().versionId()).param("nodeId", nodeId)
                .query(String.class).single();
    }

    private String documentField(String column) {
        return ownerJdbc.sql("SELECT " + column + " FROM document_version WHERE id = :id")
                .param("id", request.documentVersion().versionId()).query(String.class).single();
    }

    private long documentRevision() {
        return ownerJdbc.sql("SELECT revision FROM document WHERE id = :id")
                .param("id", request.documentVersion().documentId()).query(Long.class).single();
    }

    private String jobField(UUID id, String column) {
        return ownerJdbc.sql("SELECT " + column + " FROM ingestion_job WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    private UUID jobToken(UUID id) {
        return ownerJdbc.sql("SELECT lease_token FROM ingestion_job WHERE id = :id")
                .param("id", id).query(UUID.class).single();
    }

    @TestConfiguration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfig {
        static DataSource dataSource;

        @Bean DataSource dataSource() { return dataSource; }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean JdbcClient jdbcClient(DataSource dataSource) { return JdbcClient.create(dataSource); }
        @Bean JdbcIngestionJobRepository jobs(JdbcClient jdbc) { return new JdbcIngestionJobRepository(jdbc); }
        @Bean JdbcParsedDocumentStore parsed(JdbcClient jdbc) { return new JdbcParsedDocumentStore(jdbc); }
        @Bean JdbcDocumentRepository documents(JdbcClient jdbc) { return new JdbcDocumentRepository(jdbc); }
        @Bean JdbcDocumentReviewStore reviews(JdbcClient jdbc) { return new JdbcDocumentReviewStore(jdbc); }
        @Bean ProjectionEventRepository projectionEvents(JdbcClient jdbc) {
            return new JdbcProjectionEventRepository(jdbc);
        }
        @Bean ProjectionDocumentLock projectionDocumentLock(JdbcClient jdbc) {
            return new JdbcProjectionDocumentLock(jdbc);
        }
        @Bean ProjectionCutoverLock projectionCutoverLock(JdbcClient jdbc) {
            return new JdbcProjectionCutoverLock(jdbc);
        }
        @Bean ProjectionChangePublisher projections(
                JdbcClient jdbc,
                ProjectionEventRepository events,
                ProjectionDocumentLock documents,
                ProjectionCutoverLock cutover) {
            return new JdbcProjectionChangePublisher(jdbc, events, documents, cutover);
        }
        @Bean JdbcAuditEventStore auditStore(JdbcClient jdbc) { return new JdbcAuditEventStore(jdbc); }
        @Bean Clock clock() { return Clock.fixed(AT.plusSeconds(1), ZoneOffset.UTC); }
        @Bean DocumentService documentService(
                JdbcDocumentRepository documents,
                JdbcDocumentReviewStore reviews,
                ProjectionChangePublisher projections) {
            var access = new AccessDecisionService(new EffectivePrincipalSet(userId -> {
                throw new UnsupportedOperationException();
            }), (spaceId, documentId, principals, at) -> java.util.Optional.empty());
            return new DocumentService(documents, access, DocumentContentGateway.unavailable(),
                    (userId, spaceId, at) -> List.of(), KnowledgeSpaceReadAuthorization.denyAll(),
                    KnowledgeWriteAuthorization.allowAll(), AuditSink.noop(), projections, reviews);
        }
        @Bean AuditService auditService(JdbcAuditEventStore store, Clock clock) {
            return new AuditService(store, clock);
        }
        @Bean IngestionJobService ingestionService(JdbcIngestionJobRepository jobs,
                                                    JdbcParsedDocumentStore parsed,
                                                    DocumentService documents,
                                                    AuditService audit,
                                                    Clock clock) {
            return new IngestionJobService(jobs, parsed, documents, audit, clock, Duration.ofSeconds(30));
        }
    }
}
