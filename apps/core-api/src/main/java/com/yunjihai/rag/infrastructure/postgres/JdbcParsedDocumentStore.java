package com.yunjihai.rag.infrastructure.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.ingestion.CanonicalBoundingBox;
import com.yunjihai.rag.ingestion.CanonicalDocumentBody;
import com.yunjihai.rag.ingestion.CanonicalNode;
import com.yunjihai.rag.ingestion.CanonicalTableCell;
import com.yunjihai.rag.ingestion.DocumentVersionId;
import com.yunjihai.rag.ingestion.InvalidParserResponseException;
import com.yunjihai.rag.ingestion.ParsedDocumentStore;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Replaces one immutable-version canonical projection inside the caller transaction. */
public class JdbcParsedDocumentStore implements ParsedDocumentStore {
    private static final int MAX_NODES = 1_000_000;
    private static final int MAX_TABLE_CELLS = 100_000;
    private static final Set<String> NODE_KINDS = Set.of(
            "TITLE", "HEADING", "PARAGRAPH", "LIST_ITEM", "TABLE", "IMAGE", "CAPTION", "PAGE_BREAK");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcClient jdbc;

    public JdbcParsedDocumentStore(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    @Transactional
    public void replace(DocumentVersionId version, CanonicalDocumentBody document, Instant at) {
        Objects.requireNonNull(version);
        Objects.requireNonNull(at);
        var rows = validateAndSerialize(document);
        try {
            var persistedVersion = jdbc.sql("""
                    SELECT id FROM document_version
                    WHERE document_id = :documentId AND id = :versionId
                    FOR UPDATE
                    """)
                    .param("documentId", version.documentId())
                    .param("versionId", version.versionId())
                    .query(java.util.UUID.class)
                    .optional();
            if (persistedVersion.isEmpty()) {
                throw new InvalidParserResponseException("解析结果对应的文档版本不存在");
            }
            jdbc.sql("DELETE FROM canonical_document WHERE version_id = :versionId")
                    .param("versionId", version.versionId()).update();
            jdbc.sql("""
                    INSERT INTO canonical_document (version_id, source_filename, media_type, page_count, parsed_at)
                    VALUES (:versionId, :sourceFilename, :mediaType, :pageCount, :parsedAt)
                    """)
                    .param("versionId", version.versionId())
                    .param("sourceFilename", document.sourceFilename())
                    .param("mediaType", document.mediaType())
                    .param("pageCount", document.pageCount())
                    .param("parsedAt", utc(at))
                    .update();
            for (int order = 0; order < rows.size(); order++) {
                var row = rows.get(order);
                var node = row.node();
                jdbc.sql("""
                        INSERT INTO canonical_node (
                            version_id, node_id, kind, body_text, parent_node_id, page,
                            bounding_box, table_cells, confidence, stable_order)
                        VALUES (
                            :versionId, :nodeId, :kind, :bodyText, :parentNodeId, :page,
                            CAST(:boundingBox AS jsonb), CAST(:tableCells AS jsonb), :confidence, :stableOrder)
                        """)
                        .param("versionId", version.versionId())
                        .param("nodeId", node.nodeId())
                        .param("kind", node.kind())
                        .param("bodyText", node.text())
                        .param("parentNodeId", node.parentNodeId())
                        .param("page", node.page())
                        .param("boundingBox", row.boundingBox())
                        .param("tableCells", row.tableCells())
                        .param("confidence", node.confidence())
                        .param("stableOrder", order)
                        .update();
            }
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private static List<NodeRow> validateAndSerialize(CanonicalDocumentBody document) {
        if (document == null || isBlank(document.sourceFilename()) || isBlank(document.mediaType())
                || document.sourceFilename().length() > 255 || document.mediaType().length() > 255
                || !wellFormedUnicode(document.sourceFilename()) || !wellFormedUnicode(document.mediaType())
                || document.pageCount() < 1 || document.pageCount() > 500
                || document.nodes() == null || document.nodes().size() > MAX_NODES) {
            throw invalid();
        }
        Map<String, CanonicalNode> nodes = new HashMap<>();
        for (var node : document.nodes()) {
            validateNode(node, document.pageCount());
            if (nodes.putIfAbsent(node.nodeId(), node) != null) throw invalid();
        }
        for (var node : document.nodes()) {
            if (node.parentNodeId() != null && !nodes.containsKey(node.parentNodeId())) throw invalid();
            rejectParentCycle(node.nodeId(), nodes);
        }
        return document.nodes().stream().map(JdbcParsedDocumentStore::serialize).toList();
    }

    private static void validateNode(CanonicalNode node, int pageCount) {
        if (node == null || isBlank(node.nodeId()) || node.nodeId().length() > 128
                || isBlank(node.kind()) || !NODE_KINDS.contains(node.kind()) || node.text() == null
                || !wellFormedUnicode(node.nodeId()) || !wellFormedUnicode(node.kind())
                || !wellFormedUnicode(node.text())
                || node.parentNodeId() != null && (node.parentNodeId().length() > 128
                    || !wellFormedUnicode(node.parentNodeId()))
                || node.page() != null && (node.page() < 1 || node.page() > pageCount)
                || node.confidence() != null && (!Double.isFinite(node.confidence())
                    || node.confidence() < 0 || node.confidence() > 1)) {
            throw invalid();
        }
        validateBox(node.bbox());
        validateCells(node.tableCells());
    }

    private static void validateBox(CanonicalBoundingBox box) {
        if (box == null) return;
        if (!Double.isFinite(box.x()) || !Double.isFinite(box.y())
                || !Double.isFinite(box.width()) || !Double.isFinite(box.height())
                || box.x() < 0 || box.y() < 0 || box.width() < 0 || box.height() < 0) {
            throw invalid();
        }
    }

    private static void validateCells(List<CanonicalTableCell> cells) {
        if (cells == null) return;
        if (cells.size() > MAX_TABLE_CELLS) throw invalid();
        Set<String> positions = new HashSet<>();
        for (var cell : cells) {
            if (cell == null || cell.row() < 0 || cell.column() < 0
                    || cell.rowSpan() < 1 || cell.columnSpan() < 1 || cell.text() == null
                    || !wellFormedUnicode(cell.text())
                    || !positions.add(cell.row() + ":" + cell.column())) {
                throw invalid();
            }
        }
    }

    private static void rejectParentCycle(String start, Map<String, CanonicalNode> nodes) {
        Set<String> visited = new HashSet<>();
        String current = start;
        while (current != null) {
            if (!visited.add(current)) throw invalid();
            var node = nodes.get(current);
            current = node == null ? null : node.parentNodeId();
        }
    }

    private static NodeRow serialize(CanonicalNode node) {
        try {
            return new NodeRow(node,
                    node.bbox() == null ? null : JSON.writeValueAsString(node.bbox()),
                    node.tableCells() == null ? null : JSON.writeValueAsString(node.tableCells()));
        } catch (JsonProcessingException failure) {
            throw invalid();
        }
    }

    private static boolean wellFormedUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static InvalidParserResponseException invalid() {
        return new InvalidParserResponseException("解析结果节点或 JSONB 结构无效");
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record NodeRow(CanonicalNode node, String boundingBox, String tableCells) { }
}
