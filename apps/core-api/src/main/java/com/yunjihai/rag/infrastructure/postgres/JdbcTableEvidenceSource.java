package com.yunjihai.rag.infrastructure.postgres;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.ingestion.CanonicalTableCell;
import com.yunjihai.rag.qa.conflict.TableEvidenceSource;
import com.yunjihai.rag.retrieval.domain.Evidence;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.List;
import java.util.Objects;

/** Reads canonical cells only for the exact current published document and node. */
public final class JdbcTableEvidenceSource implements TableEvidenceSource {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcClient jdbc;
    public JdbcTableEvidenceSource(JdbcClient jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }
    @Override public List<CanonicalTableCell> load(Evidence evidence) {
        String raw = jdbc.sql("""
                SELECT n.table_cells::text FROM document d
                JOIN document_version v ON v.id=d.current_published_version_id AND v.document_id=d.id
                JOIN canonical_node n ON n.version_id=v.id
                WHERE d.id=:document AND d.space_id=:space AND d.archived=false
                  AND v.id=:version AND v.status='PUBLISHED' AND v.parse_status='SUCCEEDED'
                  AND n.node_id=:node AND n.kind='TABLE' AND n.table_cells IS NOT NULL
                """).param("document", evidence.documentId()).param("space", evidence.spaceId())
                .param("version", evidence.versionId()).param("node", evidence.nodeId())
                .query(String.class).optional().orElseThrow(() -> new IllegalStateException("TABLE_EVIDENCE_UNAVAILABLE"));
        try {
            var cells = JSON.readValue(raw, new TypeReference<List<CanonicalTableCell>>() {});
            if (cells == null || cells.isEmpty() || cells.size() > 4096) throw new IllegalStateException("INVALID_TABLE_EVIDENCE");
            return List.copyOf(cells);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("INVALID_TABLE_EVIDENCE", failure);
        }
    }
}
