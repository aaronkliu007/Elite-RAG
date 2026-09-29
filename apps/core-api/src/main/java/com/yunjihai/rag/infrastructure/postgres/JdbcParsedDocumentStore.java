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
    private static final int NODE_BATCH_SIZE = 1_000;
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
            insertNodeBatches(version, rows);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private static List<NodeRow> validateAndSerialize(CanonicalDocumentBody document) {
        if (document == null || isBlank(document.sourceFilename()) || isBlank(document.mediaType())
                || document.sourceFilename().length() > 255 || document.mediaType().length() > 255
                || !wellFormedUnicode(document.sourceFilename()) || !wellFormedUnicode(document.mediaType())
                || document.pageCount() < 1 || document.pageCount() > 500
                || document.nodes() == null) {
            throw invalid();
        }
        var sourceNodes = document.nodes();
        validateNodeCount(sourceNodes.size());
        Map<String, Integer> indexes = new HashMap<>(hashCapacity(sourceNodes.size()));
        for (int index = 0; index < sourceNodes.size(); index++) {
            var node = sourceNodes.get(index);
            validateNode(node, document.pageCount());
            if (indexes.putIfAbsent(node.nodeId(), index) != null) throw invalid();
        }
        int[] parents = new int[sourceNodes.size()];
        java.util.Arrays.fill(parents, -1);
        for (int index = 0; index < sourceNodes.size(); index++) {
            String parentId = sourceNodes.get(index).parentNodeId();
            if (parentId == null) continue;
            Integer parent = indexes.get(parentId);
            if (parent == null) throw invalid();
            parents[index] = parent;
        }
        rejectParentCycles(parents);
        return sourceNodes.stream().map(JdbcParsedDocumentStore::serialize).toList();
    }

    static void validateNodeCount(int count) {
        if (count < 0 || count > MAX_NODES) throw invalid();
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

    private static void rejectParentCycles(int[] parents) {
        byte[] states = new byte[parents.length];
        for (int start = 0; start < parents.length; start++) {
            int current = start;
            while (current >= 0 && states[current] == 0) {
                states[current] = 1;
                current = parents[current];
            }
            if (current >= 0 && states[current] == 1) throw invalid();
            current = start;
            while (current >= 0 && states[current] == 1) {
                states[current] = 2;
                current = parents[current];
            }
        }
    }

    private void insertNodeBatches(DocumentVersionId version, List<NodeRow> rows) {
        for (int start = 0; start < rows.size(); start += NODE_BATCH_SIZE) {
            int end = Math.min(start + NODE_BATCH_SIZE, rows.size());
            var sql = new StringBuilder("""
                    INSERT INTO canonical_node (
                        version_id, node_id, kind, body_text, parent_node_id, page,
                        bounding_box, table_cells, confidence, stable_order)
                    VALUES
                    """);
            for (int index = start; index < end; index++) {
                if (index > start) sql.append(",\n");
                String suffix = Integer.toString(index - start);
                sql.append("(:versionId, :nodeId").append(suffix)
                        .append(", :kind").append(suffix)
                        .append(", :bodyText").append(suffix)
                        .append(", :parentNodeId").append(suffix)
                        .append(", :page").append(suffix)
                        .append(", CAST(:boundingBox").append(suffix).append(" AS jsonb)")
                        .append(", CAST(:tableCells").append(suffix).append(" AS jsonb)")
                        .append(", :confidence").append(suffix)
                        .append(", :stableOrder").append(suffix).append(')');
            }
            JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("versionId", version.versionId());
            for (int index = start; index < end; index++) {
                int offset = index - start;
                String suffix = Integer.toString(offset);
                var row = rows.get(index);
                var node = row.node();
                statement = statement
                        .param("nodeId" + suffix, node.nodeId())
                        .param("kind" + suffix, node.kind())
                        .param("bodyText" + suffix, node.text())
                        .param("parentNodeId" + suffix, node.parentNodeId())
                        .param("page" + suffix, node.page())
                        .param("boundingBox" + suffix, row.boundingBox())
                        .param("tableCells" + suffix, row.tableCells())
                        .param("confidence" + suffix, node.confidence())
                        .param("stableOrder" + suffix, index);
            }
            if (statement.update() != end - start) {
                throw new IllegalStateException("CANONICAL_NODE_BATCH_WRITE_FAILURE");
            }
        }
    }

    private static int hashCapacity(int size) {
        if (size < 3) return 4;
        return Math.min(1 << 30, (int) Math.ceil(size / 0.75d));
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
