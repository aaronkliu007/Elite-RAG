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

    @Override public boolean hasStandaloneBudgetContext(Evidence evidence) {
        var nodes=loadContext(evidence);
        if(nodes.isEmpty() || nodes.size()>32)return false;
        int tables=0;
        for(var node:nodes) {
            if("TABLE".equals(node[1])) {
                if(!node[0].equals(evidence.nodeId()) || !"研发预算".equals(node[2]) || !node[2].equals(evidence.excerpt()))return false;
                tables++;
            } else if(!"HEADING".equals(node[1]) || !List.of("预算","研发预算","预算表","预算明细").contains(node[2]))return false;
        }
        return tables==1;
    }

    @Override public boolean hasStandaloneBackupContext(Evidence evidence) {
        var nodes=loadContext(evidence);
        if(nodes.isEmpty() || nodes.size()>32)return false;
        int paragraphs=0;
        for(var node:nodes) {
            if("PARAGRAPH".equals(node[1])) {
                if(!node[0].equals(evidence.nodeId()) || !node[2].equals(evidence.excerpt()))return false;
                paragraphs++;
            } else if(!"HEADING".equals(node[1]) || !List.of("章节","备份制度","数据备份制度").contains(node[2]))return false;
        }
        return paragraphs==1;
    }

    private List<String[]> loadContext(Evidence evidence) {
        var document=jdbc.sql("""
                SELECT c.version_id FROM document d
                JOIN document_version v ON v.id=d.current_published_version_id AND v.document_id=d.id
                JOIN canonical_document c ON c.version_id=v.id
                WHERE d.id=:document AND d.space_id=:space AND d.title=:title AND d.archived=false
                  AND v.id=:version AND v.status='PUBLISHED' AND v.parse_status='SUCCEEDED'
                """).param("document",evidence.documentId()).param("space",evidence.spaceId()).param("title",evidence.title())
                .param("version",evidence.versionId()).query(java.util.UUID.class).optional();
        if(document.isEmpty())return List.of();
        var nodes=jdbc.sql("""
                SELECT n.node_id,n.kind,n.body_text FROM document d
                JOIN document_version v ON v.id=d.current_published_version_id AND v.document_id=d.id
                JOIN canonical_node n ON n.version_id=v.id
                WHERE d.id=:document AND d.space_id=:space AND d.archived=false
                  AND v.id=:version AND v.status='PUBLISHED' AND v.parse_status='SUCCEEDED'
                ORDER BY n.stable_order LIMIT 33
                """).param("document",evidence.documentId()).param("space",evidence.spaceId())
                .param("version",evidence.versionId())
                .query((rs,row)->new String[]{rs.getString("node_id"),rs.getString("kind"),rs.getString("body_text")}).list();
        return nodes;
    }
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
