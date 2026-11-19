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

    public java.util.Optional<String> verifiedContextFingerprint(Evidence evidence) {
        var fingerprint=jdbc.sql("""
                SELECT qa_document_context_fingerprint(d.id,n.version_id) FROM document d
                JOIN document_version v ON v.id=d.current_published_version_id AND v.document_id=d.id
                JOIN canonical_node n ON n.version_id=v.id
                WHERE d.id=:document AND d.space_id=:space AND d.title=:title AND NOT d.archived
                  AND v.id=:version AND v.status='PUBLISHED' AND v.parse_status='SUCCEEDED'
                  AND n.node_id=:node AND (n.kind='TABLE')=:isTable
                  AND n.body_text=:excerpt
                """).param("document",evidence.documentId()).param("space",evidence.spaceId())
                .param("title",evidence.title()).param("version",evidence.versionId())
                .param("node",evidence.nodeId()).param("isTable",evidence.locator() instanceof com.yunjihai.rag.retrieval.domain.TableLocator)
                .param("excerpt",evidence.excerpt())
                .query(String.class).optional();
        if(fingerprint.isEmpty())return java.util.Optional.empty();
        long count=jdbc.sql("SELECT count(*) FROM (SELECT 1 FROM canonical_node WHERE version_id=:version LIMIT 513) nodes")
                .param("version",evidence.versionId()).query(Long.class).single();
        if(count>512)return java.util.Optional.empty();
        var canonical=new JdbcRetrievalProjectionSource(jdbc).load(evidence.spaceId(),evidence.documentId(),0);
        if(!canonical.indexable() || !evidence.versionId().equals(canonical.versionId()) || !evidence.title().equals(canonical.title()))
            return java.util.Optional.empty();
        boolean matches=canonical.nodes().stream().anyMatch(node->node.nodeId().equals(evidence.nodeId())
                && node.bodyText().equals(evidence.excerpt()) && node.locator().equals(evidence.locator()));
        if(matches && evidence.locator() instanceof com.yunjihai.rag.retrieval.domain.TableLocator
                && !containsCompleteTable(evidence))return java.util.Optional.empty();
        return matches ? fingerprint : java.util.Optional.empty();
    }

    /** Ensures text coverage and rejects ambiguous cells; semantic interpretation still needs separate review. */
    private boolean containsCompleteTable(Evidence evidence) {
        try {
            var cells=load(evidence); var occupied=new java.util.HashSet<Long>();
            for(var cell:cells)if(cell==null || cell.row()<0 || cell.column()<0 || cell.rowSpan()<1 || cell.columnSpan()<1
                    || cell.text()==null || cell.text().codePoints().allMatch(cp->Character.isWhitespace(cp) || Character.isSpaceChar(cp))
                    || cell.text().matches("(?s).*\\R.*"))return false;
            for(var cell:cells) {
                long rowEnd=(long)cell.row()+cell.rowSpan(),columnEnd=(long)cell.column()+cell.columnSpan();
                if(rowEnd>Integer.MAX_VALUE || columnEnd>Integer.MAX_VALUE
                        || (long)cell.rowSpan()*cell.columnSpan()>16384
                        || occupied.size()+(long)cell.rowSpan()*cell.columnSpan()>16384)return false;
                for(long row=cell.row();row<rowEnd;row++) for(long column=cell.column();column<columnEnd;column++)
                    if(!occupied.add((row<<32)|column))return false;
            }
            var ordered=cells.stream().sorted(java.util.Comparator.comparingInt(CanonicalTableCell::row)
                    .thenComparingInt(CanonicalTableCell::column)).toList();
            return evidence.excerpt().equals(ordered.stream().map(CanonicalTableCell::text)
                    .collect(java.util.stream.Collectors.joining("\n")));
        } catch(RuntimeException invalid) { return false; }
    }

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
