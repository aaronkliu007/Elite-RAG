package com.yunjihai.rag.infrastructure.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.ingestion.CanonicalBoundingBox;
import com.yunjihai.rag.ingestion.CanonicalTableCell;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.domain.SectionLocator;
import com.yunjihai.rag.retrieval.domain.NormalizedBoundingBox;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import com.yunjihai.rag.retrieval.domain.SlideLocator;
import com.yunjihai.rag.retrieval.domain.SourceLocator;
import com.yunjihai.rag.retrieval.domain.TableLocator;
import com.yunjihai.rag.retrieval.projection.ProjectionAclGrant;
import com.yunjihai.rag.retrieval.projection.ProjectionNode;
import com.yunjihai.rag.retrieval.projection.RetrievalProjectionSnapshot;
import com.yunjihai.rag.retrieval.projection.RetrievalProjectionSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Loads a rebuildable search projection solely from current PostgreSQL authority. */
public final class JdbcRetrievalProjectionSource implements RetrievalProjectionSource {
    private static final Set<String> NODE_KINDS = Set.of(
            "TITLE", "HEADING", "PARAGRAPH", "LIST_ITEM", "TABLE", "IMAGE", "CAPTION", "PAGE_BREAK");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<CanonicalTableCell>> TABLE_CELLS = new TypeReference<>() { };
    private final JdbcClient jdbc;

    public JdbcRetrievalProjectionSource(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public RetrievalProjectionSnapshot load(
            UUID spaceId, UUID documentId, long projectionSequence) {
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(documentId);
        if (projectionSequence < 0) throw new IllegalArgumentException("投影序号不能为负数");
        try {
            var authority = jdbc.sql("""
                    SELECT document.id, document.space_id, document.title, document.archived,
                           document.revision, version.id AS version_id, version.status,
                           version.parse_status, version.status_changed_at,
                           canonical.source_filename, canonical.media_type,
                           canonical.page_count, canonical.parsed_at,
                           metadata.industry, metadata.product,
                           metadata.solution_type, metadata.year
                    FROM document
                    LEFT JOIN document_version version
                      ON version.document_id = document.id
                     AND version.id = document.current_published_version_id
                    LEFT JOIN canonical_document canonical ON canonical.version_id = version.id
                    LEFT JOIN document_retrieval_metadata metadata ON metadata.document_id = document.id
                    WHERE document.id = :documentId AND document.space_id = :spaceId
                    """)
                    .param("documentId", documentId)
                    .param("spaceId", spaceId)
                    .query(JdbcRetrievalProjectionSource::mapAuthority)
                    .optional();
            if (authority.isEmpty()) {
                return RetrievalProjectionSnapshot.deletion(spaceId, documentId, 0, projectionSequence);
            }
            var document = authority.orElseThrow();
            if (!document.indexable()) {
                return RetrievalProjectionSnapshot.deletion(
                        spaceId, documentId, document.revision(), projectionSequence);
            }
            var rawNodes = loadNodes(document.versionId());
            if (rawNodes.isEmpty()) {
                return RetrievalProjectionSnapshot.deletion(
                        spaceId, documentId, document.revision(), projectionSequence);
            }
            var nodes = convertNodes(document, rawNodes);
            var acl = loadAcl(spaceId, documentId);
            return new RetrievalProjectionSnapshot(
                    spaceId, documentId, document.versionId(), document.title(), document.revision(),
                    projectionSequence, document.publishedAt().toInstant(), document.parsedAt().toInstant(),
                    acl.spaceGrants(), acl.documentGrants(), acl.documentRestricted(),
                    document.metadata(), nodes, true);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public List<RetrievalProjectionSnapshot> pageSpace(
            UUID spaceId, UUID afterDocumentId, int limit, long projectionSequence) {
        Objects.requireNonNull(spaceId);
        return page(spaceId, afterDocumentId, limit, projectionSequence);
    }

    @Override
    public List<RetrievalProjectionSnapshot> pageAll(
            UUID afterDocumentId, int limit, long projectionSequence) {
        return page(null, afterDocumentId, limit, projectionSequence);
    }

    private List<RetrievalProjectionSnapshot> page(
            UUID spaceId, UUID afterDocumentId, int limit, long projectionSequence) {
        if (limit <= 0 || limit > 1_000 || projectionSequence < 0) {
            throw new IllegalArgumentException("投影分页参数无效");
        }
        try {
            StringBuilder sql = new StringBuilder("SELECT id, space_id FROM document WHERE 1=1");
            if (spaceId != null) sql.append(" AND space_id = :spaceId");
            if (afterDocumentId != null) sql.append(" AND id > :afterDocumentId");
            sql.append(" ORDER BY id LIMIT :limit");
            var statement = jdbc.sql(sql.toString()).param("limit", limit);
            if (spaceId != null) statement = statement.param("spaceId", spaceId);
            if (afterDocumentId != null) statement = statement.param("afterDocumentId", afterDocumentId);
            var documents = statement.query((row, number) -> new DocumentKey(
                    row.getObject("space_id", UUID.class), row.getObject("id", UUID.class))).list();
            return documents.stream()
                    .map(document -> load(document.spaceId(), document.documentId(), projectionSequence))
                    .toList();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private List<RawNode> loadNodes(UUID versionId) {
        return jdbc.sql("""
                SELECT node_id, kind, body_text, parent_node_id, page,
                       bounding_box::text, table_cells::text, confidence, stable_order
                FROM canonical_node
                WHERE version_id = :versionId
                ORDER BY stable_order
                """)
                .param("versionId", versionId)
                .query(JdbcRetrievalProjectionSource::mapNode)
                .list();
    }

    private AclProjection loadAcl(UUID spaceId, UUID documentId) {
        var rows = jdbc.sql("""
                SELECT scope, principal_type, principal_id, expires_at, revoked_at
                FROM acl_entry
                WHERE space_id = :spaceId
                  AND (scope = 'SPACE' OR document_id = :documentId)
                ORDER BY scope, principal_type, principal_id, created_at
                """)
                .param("spaceId", spaceId)
                .param("documentId", documentId)
                .query((row, number) -> new AclRow(
                        row.getString("scope"),
                        row.getString("principal_type") + ":" + row.getObject("principal_id", UUID.class),
                        row.getObject("expires_at", OffsetDateTime.class),
                        row.getObject("revoked_at", OffsetDateTime.class)))
                .list();
        boolean restricted = rows.stream().anyMatch(row -> row.scope().equals("DOCUMENT"));
        Set<ProjectionAclGrant> space = new LinkedHashSet<>();
        Set<ProjectionAclGrant> document = new LinkedHashSet<>();
        for (var row : rows) {
            if (row.revokedAt() != null) continue;
            var grant = new ProjectionAclGrant(
                    row.principalKey(), row.expiresAt() == null ? null : row.expiresAt().toInstant());
            (row.scope().equals("SPACE") ? space : document).add(grant);
        }
        return new AclProjection(Set.copyOf(space), Set.copyOf(document), restricted);
    }

    private static List<ProjectionNode> convertNodes(Authority authority, List<RawNode> rawNodes) {
        Map<String, RawNode> byId = new HashMap<>();
        Set<Integer> orders = new HashSet<>();
        for (var node : rawNodes) {
            validateRawNode(node, authority.pageCount());
            if (byId.putIfAbsent(node.nodeId(), node) != null || !orders.add(node.stableOrder())) {
                throw invalid();
            }
        }
        for (var node : rawNodes) {
            if (node.parentNodeId() != null && !byId.containsKey(node.parentNodeId())) throw invalid();
            rejectCycle(node, byId);
        }
        var converted = new ArrayList<ProjectionNode>(rawNodes.size());
        for (var node : rawNodes) {
            List<String> headingPath = headingPath(node, byId);
            SourceLocator locator = locator(authority, node, headingPath);
            String stableId = authority.versionId() + ":" + node.nodeId();
            converted.add(new ProjectionNode(
                    stableId,
                    UUID.nameUUIDFromBytes(stableId.getBytes(StandardCharsets.UTF_8)),
                    node.nodeId(), node.kind(), node.bodyText(), headingPath,
                    node.stableOrder(), locator, node.confidence()));
        }
        return List.copyOf(converted);
    }

    private static void validateRawNode(RawNode node, int pageCount) {
        if (node.nodeId() == null || node.nodeId().isBlank() || node.nodeId().length() > 128
                || node.kind() == null || !NODE_KINDS.contains(node.kind())
                || node.bodyText() == null || node.bodyText().isBlank()
                || node.bodyText().codePointCount(0, node.bodyText().length()) > 20_000
                || node.parentNodeId() != null && node.parentNodeId().length() > 128
                || node.page() != null && (node.page() < 1 || node.page() > pageCount)
                || node.stableOrder() < 0
                || node.confidence() != null && (!Double.isFinite(node.confidence())
                    || node.confidence() < 0 || node.confidence() > 1)) {
            throw invalid();
        }
        parseBox(node.boundingBox());
        parseCells(node.tableCells());
    }

    private static void rejectCycle(RawNode start, Map<String, RawNode> byId) {
        Set<String> visited = new HashSet<>();
        RawNode current = start;
        while (current.parentNodeId() != null) {
            if (!visited.add(current.nodeId())) throw invalid();
            current = byId.get(current.parentNodeId());
            if (current == null) throw invalid();
        }
    }

    private static List<String> headingPath(RawNode node, Map<String, RawNode> byId) {
        var reversed = new ArrayList<String>();
        String parentId = node.parentNodeId();
        while (parentId != null) {
            var parent = byId.get(parentId);
            if (parent == null) throw invalid();
            if (parent.kind().equals("HEADING")) reversed.add(parent.bodyText().strip());
            parentId = parent.parentNodeId();
        }
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private static SourceLocator locator(Authority authority, RawNode node, List<String> headingPath) {
        var cells = parseCells(node.tableCells());
        if (node.kind().equals("TABLE")) {
            if (cells == null || cells.isEmpty()) throw invalid();
            int startRow = Integer.MAX_VALUE;
            int endRow = 0;
            int startColumn = Integer.MAX_VALUE;
            int endColumn = 0;
            for (var cell : cells) {
                if (cell == null || cell.row() < 0 || cell.column() < 0
                        || cell.rowSpan() < 1 || cell.columnSpan() < 1 || cell.text() == null) {
                    throw invalid();
                }
                startRow = Math.min(startRow, cell.row() + 1);
                endRow = Math.max(endRow, cell.row() + cell.rowSpan());
                startColumn = Math.min(startColumn, cell.column() + 1);
                endColumn = Math.max(endColumn, cell.column() + cell.columnSpan());
            }
            String tableName = headingPath.isEmpty() ? authority.title() : headingPath.getLast();
            return new TableLocator(tableName, startRow, endRow, startColumn, endColumn);
        }
        if (authority.mediaType().equals("text/markdown")) {
            return new MarkdownLocator(headingPath, node.nodeId());
        }
        if (node.page() == null && authority.mediaType().equals(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
            return new SectionLocator(headingPath, node.nodeId());
        }
        if (node.page() == null) throw invalid();
        if (authority.mediaType().contains("presentation")) return new SlideLocator(node.page());
        return new PageLocator(node.page(), parseBox(node.boundingBox()));
    }

    private static NormalizedBoundingBox parseBox(String json) {
        if (json == null || json.equals("null")) return null;
        try {
            CanonicalBoundingBox box = JSON.readValue(json, CanonicalBoundingBox.class);
            if (box == null) return null;
            return new NormalizedBoundingBox(
                    box.x(), box.y(), box.x() + box.width(), box.y() + box.height());
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static List<CanonicalTableCell> parseCells(String json) {
        if (json == null || json.equals("null")) return null;
        try {
            return JSON.readValue(json, TABLE_CELLS);
        } catch (JsonProcessingException failure) {
            throw invalid();
        }
    }

    private static Authority mapAuthority(ResultSet row, int rowNumber) throws SQLException {
        UUID versionId = row.getObject("version_id", UUID.class);
        Integer pageCount = row.getObject("page_count", Integer.class);
        var metadata = new LinkedHashMap<String, String>();
        add(metadata, "industry", row.getString("industry"));
        add(metadata, "product", row.getString("product"));
        add(metadata, "solutionType", row.getString("solution_type"));
        Integer year = row.getObject("year", Integer.class);
        if (year != null) metadata.put("year", Integer.toString(year));
        return new Authority(
                row.getObject("space_id", UUID.class), row.getObject("id", UUID.class),
                row.getString("title"), row.getBoolean("archived"), row.getLong("revision"),
                versionId, row.getString("status"), row.getString("parse_status"),
                row.getObject("status_changed_at", OffsetDateTime.class),
                row.getString("source_filename"), row.getString("media_type"),
                pageCount, row.getObject("parsed_at", OffsetDateTime.class), Map.copyOf(metadata));
    }

    private static RawNode mapNode(ResultSet row, int rowNumber) throws SQLException {
        return new RawNode(
                row.getString("node_id"), row.getString("kind"), row.getString("body_text"),
                row.getString("parent_node_id"), row.getObject("page", Integer.class),
                row.getString("bounding_box"), row.getString("table_cells"),
                row.getObject("confidence", Double.class), row.getInt("stable_order"));
    }

    private static void add(Map<String, String> metadata, String key, String value) {
        if (value != null) metadata.put(key, value);
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("INVALID_RETRIEVAL_PROJECTION");
    }

    private record DocumentKey(UUID spaceId, UUID documentId) { }

    private record AclRow(
            String scope, String principalKey, OffsetDateTime expiresAt, OffsetDateTime revokedAt) { }

    private record AclProjection(
            Set<ProjectionAclGrant> spaceGrants,
            Set<ProjectionAclGrant> documentGrants,
            boolean documentRestricted) { }

    private record RawNode(
            String nodeId,
            String kind,
            String bodyText,
            String parentNodeId,
            Integer page,
            String boundingBox,
            String tableCells,
            Double confidence,
            int stableOrder) { }

    private record Authority(
            UUID spaceId,
            UUID documentId,
            String title,
            boolean archived,
            long revision,
            UUID versionId,
            String status,
            String parseStatus,
            OffsetDateTime publishedAt,
            String sourceFilename,
            String mediaType,
            Integer pageCount,
            OffsetDateTime parsedAt,
            Map<String, String> metadata) {
        boolean indexable() {
            return !archived && versionId != null && "PUBLISHED".equals(status)
                    && "SUCCEEDED".equals(parseStatus) && sourceFilename != null
                    && mediaType != null && pageCount != null && pageCount > 0
                    && publishedAt != null && parsedAt != null;
        }
    }
}
