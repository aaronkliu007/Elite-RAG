package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.access.AccessDecisionService;
import com.yunjihai.rag.access.EffectivePrincipalSet;
import com.yunjihai.rag.audit.AuditService;
import com.yunjihai.rag.ingestion.CanonicalBoundingBox;
import com.yunjihai.rag.ingestion.CanonicalDocumentBody;
import com.yunjihai.rag.ingestion.CanonicalDocumentV1;
import com.yunjihai.rag.ingestion.CanonicalNode;
import com.yunjihai.rag.ingestion.CanonicalTableCell;
import com.yunjihai.rag.ingestion.IngestionJobService;
import com.yunjihai.rag.ingestion.InvalidParserResponseException;
import com.yunjihai.rag.ingestion.ParserStatus;
import com.yunjihai.rag.knowledge.DocumentContentGateway;
import com.yunjihai.rag.knowledge.DocumentService;
import com.yunjihai.rag.knowledge.KnowledgeSpaceReadAuthorization;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IngestionCompletionTransactionIntegrationTest extends IngestionPostgresFixture {
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
    void successfulCompletionAtomicallyPersistsCanonicalReviewJobAndAuditState() {
        UUID jobId = UUID.randomUUID();
        jobs.enqueue(jobId, request, AT);
        var lease = jobs.claimNext("worker-a", AT, AT.plusSeconds(30)).orElseThrow();

        try (var context = serviceContext()) {
            context.getBean(IngestionJobService.class).complete(lease, result(jobId));
        }

        assertEquals(1L, count("canonical_document"));
        assertEquals(2L, count("canonical_node"));
        assertEquals("IN_REVIEW", documentField("status"));
        assertEquals("SUCCEEDED", documentField("parse_status"));
        assertEquals(1L, documentRevision());
        assertEquals("SUCCEEDED", jobField(jobId, "status"));
        assertEquals(1L, count("audit_event"));
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
        @Bean JdbcAuditEventStore auditStore(JdbcClient jdbc) { return new JdbcAuditEventStore(jdbc); }
        @Bean Clock clock() { return Clock.fixed(AT.plusSeconds(1), ZoneOffset.UTC); }
        @Bean DocumentService documentService(JdbcDocumentRepository documents) {
            var access = new AccessDecisionService(new EffectivePrincipalSet(userId -> {
                throw new UnsupportedOperationException();
            }), (spaceId, documentId, principals, at) -> java.util.Optional.empty());
            return new DocumentService(documents, access, DocumentContentGateway.unavailable(),
                    (userId, spaceId, at) -> List.of(), KnowledgeSpaceReadAuthorization.denyAll(),
                    KnowledgeWriteAuthorization.allowAll());
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
