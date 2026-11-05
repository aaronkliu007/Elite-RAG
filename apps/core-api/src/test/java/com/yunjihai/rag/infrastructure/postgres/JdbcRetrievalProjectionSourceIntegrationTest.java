package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import com.yunjihai.rag.retrieval.domain.TableLocator;
import com.yunjihai.rag.retrieval.projection.ProjectionAclGrant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcRetrievalProjectionSourceIntegrationTest {
    private static final long SEQUENCE = 41L;
    private static final Instant PUBLISHED_AT = Instant.parse("2026-09-30T10:30:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-30T10:30:00Z");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_projection_source_test")
            .withUsername("migration_owner")
            .withPassword("migration_test_only");

    private static JdbcClient jdbc;
    private static JdbcRetrievalProjectionSource source;
    private UUID actorId;
    private UUID spaceId;

    @BeforeAll
    static void startDatabase() throws Exception {
        POSTGRES.start();
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_projection_source_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only"));
        source = new JdbcRetrievalProjectionSource(jdbc);
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @BeforeEach
    void seedScope() {
        jdbc.sql("DELETE FROM retrieval_projection_event").update();
        jdbc.sql("DELETE FROM document_retrieval_metadata").update();
        jdbc.sql("DELETE FROM acl_entry").update();
        jdbc.sql("DELETE FROM canonical_node").update();
        jdbc.sql("DELETE FROM canonical_document").update();
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
    void loadsCompleteIndexableSnapshotWithLocatorsAclAndWhitelistedMetadata() {
        var ids = publishedDocument(spaceId, false, true, "text/markdown", 4);
        insertNode(ids.versionId(), "h1", "HEADING", "章节", null, 1, null, null, 0);
        insertNode(ids.versionId(), "h2", "HEADING", "小节", "h1", 1, null, null, 1);
        insertNode(ids.versionId(), "paragraph", "PARAGRAPH", "正文", "h2", 1,
                "{\"x\":0.1,\"y\":0.2,\"width\":0.3,\"height\":0.4}", null, 2);
        insertNode(ids.versionId(), "table", "TABLE", "报价表", "h2", 1, null,
                "[{\"row\":1,\"column\":2,\"rowSpan\":2,\"columnSpan\":3,\"text\":\"100\"}]", 3);
        jdbc.sql("""
                INSERT INTO document_retrieval_metadata
                    (document_id, industry, product, solution_type, year)
                VALUES (:document, '软件', 'RAG', '解决方案', 2026)
                """).param("document", ids.documentId()).update();
        grant(ids.documentId(), "SPACE", "USER", actorId, EXPIRES_AT);
        UUID department = UUID.randomUUID();
        grant(ids.documentId(), "DOCUMENT", "DEPARTMENT", department, null);

        var snapshot = source.load(spaceId, ids.documentId(), SEQUENCE);

        assertTrue(snapshot.indexable());
        assertFalse(snapshot.deleteProjection());
        assertEquals(SEQUENCE, snapshot.projectionSequence());
        assertEquals(4, snapshot.documentRevision());
        assertEquals(Map.of(
                "industry", "软件", "product", "RAG",
                "solutionType", "解决方案", "year", "2026"), snapshot.metadata());
        assertEquals(Set.of(new ProjectionAclGrant("USER:" + actorId, EXPIRES_AT)),
                snapshot.spaceAclGrants());
        assertEquals(Set.of(new ProjectionAclGrant("DEPARTMENT:" + department, null)),
                snapshot.documentAclGrants());
        assertTrue(snapshot.documentRestricted());
        assertEquals(List.of("h1", "h2", "paragraph", "table"),
                snapshot.nodes().stream().map(node -> node.nodeId()).toList());
        assertEquals(List.of("章节", "小节"), snapshot.nodes().get(2).headingPath());
        var markdown = assertInstanceOf(MarkdownLocator.class, snapshot.nodes().get(2).locator());
        assertEquals("paragraph", markdown.nodeId());
        assertEquals(List.of("章节", "小节"), markdown.headingPath());
        var table = assertInstanceOf(TableLocator.class, snapshot.nodes().get(3).locator());
        assertEquals(2, table.startRow());
        assertEquals(3, table.endRow());
        assertEquals(3, table.startColumn());
        assertEquals(5, table.endColumn());
        assertEquals(ids.versionId() + ":paragraph", snapshot.nodes().get(2).indexDocumentId());
        assertEquals(UUID.nameUUIDFromBytes((ids.versionId() + ":paragraph")
                        .getBytes(StandardCharsets.UTF_8)),
                snapshot.nodes().get(2).candidateId());
    }

    @Test
    void returnsDeleteSnapshotForUnavailableOrNonIndexableAuthorityStates() {
        UUID missing = UUID.randomUUID();
        var draft = document(spaceId, "DRAFT", "SUCCEEDED", false, false, false, 1);
        var archived = publishedDocument(spaceId, true, true, "text/markdown", 2);
        insertNode(archived.versionId(), "archived", "PARAGRAPH", "x", null, 1, null, null, 0);
        var missingCanonical = publishedDocument(spaceId, false, false, "text/markdown", 3);
        var emptyCanonical = publishedDocument(spaceId, false, true, "text/markdown", 4);

        for (var target : List.of(
                source.load(spaceId, missing, SEQUENCE),
                source.load(spaceId, draft.documentId(), SEQUENCE),
                source.load(spaceId, archived.documentId(), SEQUENCE),
                source.load(spaceId, missingCanonical.documentId(), SEQUENCE),
                source.load(spaceId, emptyCanonical.documentId(), SEQUENCE))) {
            assertTrue(target.deleteProjection());
            assertFalse(target.indexable());
            assertTrue(target.nodes().isEmpty());
        }
    }

    @Test
    void rejectsWholeSnapshotWhenAnyCanonicalNodeIsInvalid() {
        var ids = publishedDocument(spaceId, false, true, "text/markdown", 1);
        insertNode(ids.versionId(), "valid", "PARAGRAPH", "正文", null, 1, null, null, 0);
        insertNode(ids.versionId(), "invalid", "PARAGRAPH", "坏节点", "missing", 1, null, null, 1);

        var failure = assertThrows(IllegalStateException.class,
                () -> source.load(spaceId, ids.documentId(), SEQUENCE));

        assertEquals("INVALID_RETRIEVAL_PROJECTION", failure.getMessage());
    }

    @Test
    void convertsPdfPageAndNormalizedBoundingBox() {
        var ids = publishedDocument(spaceId, false, true, "application/pdf", 1);
        insertNode(ids.versionId(), "pdf-node", "PARAGRAPH", "PDF 正文", null, 1,
                "{\"x\":0.1,\"y\":0.2,\"width\":0.3,\"height\":0.4}", null, 0);

        var snapshot = source.load(spaceId, ids.documentId(), SEQUENCE);

        var locator = assertInstanceOf(PageLocator.class, snapshot.nodes().getFirst().locator());
        assertEquals(1, locator.pageNumber());
        assertEquals(0.1, locator.boundingBox().left());
        assertEquals(0.2, locator.boundingBox().top());
        assertEquals(0.4, locator.boundingBox().right(), 0.000001);
        assertEquals(0.6, locator.boundingBox().bottom(), 0.000001);
    }

    @Test
    void indexesWordParagraphWithoutInventingPageNumber() {
        var ids = publishedDocument(spaceId, false, true,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 4);
        insertNode(ids.versionId(), "h1", "HEADING", "验收流程", null, null, null, null, 0);
        insertNode(ids.versionId(), "p1", "PARAGRAPH", "验收须提交报告", "h1", null, null, null, 1);
        var locator = source.load(spaceId, ids.documentId(), SEQUENCE).nodes().get(1).locator();
        assertEquals("SectionLocator", locator.getClass().getSimpleName());
    }

    @Test
    void readsCanonicalCellsOnlyForTheMatchingCurrentPublishedDocumentAndSpace() {
        var ids = publishedDocument(spaceId, false, true,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 4);
        insertNode(ids.versionId(), "table", "TABLE", "项目\n金额\n星河\n100", null, 1, null,
                "[{\"row\":0,\"column\":0,\"rowSpan\":1,\"columnSpan\":1,\"text\":\"项目\"},"
                + "{\"row\":1,\"column\":0,\"rowSpan\":1,\"columnSpan\":1,\"text\":\"星河\"}]", 0);
        var ev = new com.yunjihai.rag.retrieval.domain.Evidence(spaceId, UUID.randomUUID(), ids.documentId(),
                ids.versionId(), "table", "预算", "项目\n星河", 1.0, new TableLocator("预算", 1, 2, 1, 1));
        var tableSource = new JdbcTableEvidenceSource(jdbc);
        assertEquals("星河", tableSource.load(ev).get(1).text());
        var wrongSpace = new com.yunjihai.rag.retrieval.domain.Evidence(UUID.randomUUID(), ev.evidenceId(),
                ev.documentId(), ev.versionId(), ev.nodeId(), ev.title(), ev.excerpt(), ev.score(), ev.locator());
        assertThrows(IllegalStateException.class, () -> tableSource.load(wrongSpace));
        jdbc.sql("UPDATE document SET current_published_version_id=NULL WHERE id=:id")
                .param("id", ids.documentId()).update();
        assertThrows(IllegalStateException.class, () -> tableSource.load(ev));
    }

    @Test
    void pagesSpaceAndAllDocumentsWithoutDuplicatesOrOmissions() {
        var expectedSpace = new HashSet<UUID>();
        for (int index = 0; index < 5; index++) {
            expectedSpace.add(publishedDocument(spaceId, false, false, "text/markdown", index).documentId());
        }
        UUID otherSpace = UUID.randomUUID();
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by) VALUES (:id, 'Other', :actor)")
                .param("id", otherSpace).param("actor", actorId).update();
        UUID otherDocument = publishedDocument(otherSpace, false, false, "text/markdown", 1).documentId();

        var seenSpace = pageSpace(spaceId, 2);
        var seenAll = pageAll(2);

        assertEquals(expectedSpace, new HashSet<>(seenSpace));
        assertEquals(seenSpace.size(), new HashSet<>(seenSpace).size());
        var allExpected = new HashSet<>(expectedSpace);
        allExpected.add(otherDocument);
        assertEquals(allExpected, new HashSet<>(seenAll));
        assertEquals(seenAll.size(), new HashSet<>(seenAll).size());
    }

    private List<UUID> pageSpace(UUID targetSpace, int limit) {
        var seen = new ArrayList<UUID>();
        UUID cursor = null;
        while (true) {
            var page = source.pageSpace(targetSpace, cursor, limit, SEQUENCE);
            if (page.isEmpty()) return seen;
            page.forEach(snapshot -> seen.add(snapshot.documentId()));
            cursor = page.getLast().documentId();
        }
    }

    private List<UUID> pageAll(int limit) {
        var seen = new ArrayList<UUID>();
        UUID cursor = null;
        while (true) {
            var page = source.pageAll(cursor, limit, SEQUENCE);
            if (page.isEmpty()) return seen;
            page.forEach(snapshot -> seen.add(snapshot.documentId()));
            cursor = page.getLast().documentId();
        }
    }

    private DocumentIds publishedDocument(
            UUID targetSpace, boolean archived, boolean canonical, String mediaType, long revision) {
        return document(targetSpace, "PUBLISHED", "SUCCEEDED", archived, true, canonical, revision, mediaType);
    }

    private DocumentIds document(
            UUID targetSpace, String status, String parseStatus, boolean archived,
            boolean current, boolean canonical, long revision) {
        return document(targetSpace, status, parseStatus, archived, current, canonical, revision, "text/markdown");
    }

    private DocumentIds document(
            UUID targetSpace, String status, String parseStatus, boolean archived,
            boolean current, boolean canonical, long revision, String mediaType) {
        UUID document = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO document (id, space_id, title, archived, revision)
                VALUES (:id, :space, '投影文档', :archived, :revision)
                """).param("id", document).param("space", targetSpace)
                .param("archived", archived).param("revision", revision).update();
        jdbc.sql("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_blob_id, status, parse_status,
                     created_by, status_changed_at)
                VALUES (:id, :document, 1, 'blob', :status, :parseStatus, :actor, :publishedAt)
                """).param("id", version).param("document", document).param("status", status)
                .param("parseStatus", parseStatus).param("actor", actorId)
                .param("publishedAt", OffsetDateTime.ofInstant(PUBLISHED_AT, java.time.ZoneOffset.UTC)).update();
        if (current) {
            jdbc.sql("UPDATE document SET current_published_version_id = :version WHERE id = :document")
                    .param("version", version).param("document", document).update();
        }
        if (canonical) {
            jdbc.sql("""
                    INSERT INTO canonical_document
                        (version_id, source_filename, media_type, page_count, parsed_at)
                    VALUES (:version, 'source.md', :mediaType, 1, :at)
                    """).param("version", version).param("mediaType", mediaType)
                    .param("at", OffsetDateTime.ofInstant(PUBLISHED_AT, java.time.ZoneOffset.UTC)).update();
        }
        return new DocumentIds(document, version);
    }

    private void insertNode(
            UUID versionId, String nodeId, String kind, String text, String parent,
            Integer page, String box, String cells, int order) {
        var statement = jdbc.sql("""
                INSERT INTO canonical_node
                    (version_id, node_id, kind, body_text, parent_node_id, page,
                     bounding_box, table_cells, confidence, stable_order)
                VALUES (:version, :node, :kind, :text, :parent, :page,
                        CAST(:box AS jsonb), CAST(:cells AS jsonb), 0.9, :stableOrder)
                """).param("version", versionId).param("node", nodeId).param("kind", kind)
                .param("text", text).param("page", page).param("stableOrder", order);
        statement = parent == null ? statement.param("parent", null, java.sql.Types.VARCHAR)
                : statement.param("parent", parent);
        statement = statement.param("box", box == null ? "null" : box)
                .param("cells", cells == null ? "null" : cells);
        statement.update();
    }

    private void grant(UUID documentId, String scope, String type, UUID principal, Instant expiresAt) {
        var statement = jdbc.sql("""
                INSERT INTO acl_entry
                    (id, space_id, document_id, scope, principal_type, principal_id, expires_at)
                VALUES (:id, :space, :document, :scope, :type, :principal, :expires)
                """).param("id", UUID.randomUUID()).param("space", spaceId)
                .param("scope", scope).param("type", type).param("principal", principal);
        statement = documentId == null || scope.equals("SPACE")
                ? statement.param("document", null, java.sql.Types.OTHER)
                : statement.param("document", documentId);
        statement = expiresAt == null
                ? statement.param("expires", null, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                : statement.param("expires", OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC));
        statement.update();
    }

    private record DocumentIds(UUID documentId, UUID versionId) { }
}
