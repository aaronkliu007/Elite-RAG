package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.solution.generation.GenerationEvidenceSource;
import com.yunjihai.rag.solution.generation.GenerationEvidenceStore;
import com.yunjihai.rag.solution.generation.GenerationRequestConflictException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class JdbcGenerationEvidenceStore implements GenerationEvidenceStore {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcGenerationEvidenceStore(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
    }

    @Override
    public void saveSnapshot(UUID attemptId, List<GenerationEvidenceSource> sources) {
        Objects.requireNonNull(attemptId);
        var snapshot = List.copyOf(Objects.requireNonNull(sources));
        if (snapshot.isEmpty()) throw new IllegalArgumentException("empty evidence snapshot");
        try {
            transactions.executeWithoutResult(status -> {
                for (var source : snapshot) {
                    jdbc.sql("""
                            INSERT INTO solution_generation_evidence_source
                                (attempt_id, evidence_id, evidence_order, space_id,
                                 document_id, version_id, node_id, excerpt_hash)
                            VALUES (:attemptId, :evidenceId, :evidenceOrder, :spaceId,
                                    :documentId, :versionId, :nodeId, :excerptHash)
                            ON CONFLICT DO NOTHING
                            """).param("attemptId", attemptId)
                            .param("evidenceId", source.evidenceId())
                            .param("evidenceOrder", source.position())
                            .param("spaceId", source.spaceId())
                            .param("documentId", source.documentId())
                            .param("versionId", source.versionId())
                            .param("nodeId", source.nodeId())
                            .param("excerptHash", source.excerptHash()).update();
                }
                if (!findByAttemptId(attemptId).equals(snapshot)) {
                    throw new GenerationRequestConflictException();
                }
            });
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public List<GenerationEvidenceSource> findByAttemptId(UUID attemptId) {
        Objects.requireNonNull(attemptId);
        return query("""
                SELECT evidence_order, evidence_id, space_id, document_id, version_id,
                       node_id, excerpt_hash
                  FROM solution_generation_evidence_source
                 WHERE attempt_id = :id
                 ORDER BY evidence_order
                """, attemptId);
    }

    @Override
    public List<GenerationEvidenceSource> findBySectionVersionId(UUID sectionVersionId) {
        Objects.requireNonNull(sectionVersionId);
        return query("""
                WITH RECURSIVE ancestry AS (
                    SELECT id, parent_version_id, attempt_id, kind, ARRAY[id] AS visited
                    FROM solution_section_version WHERE id = :id
                    UNION ALL
                    SELECT parent.id, parent.parent_version_id, parent.attempt_id, parent.kind,
                           child.visited || parent.id
                    FROM solution_section_version parent
                    JOIN ancestry child ON parent.id = child.parent_version_id
                    WHERE child.kind <> 'MODEL_DRAFT' AND parent.id <> ALL(child.visited)
                )
                SELECT source.evidence_order, source.evidence_id, source.space_id,
                       source.document_id, source.version_id, source.node_id, source.excerpt_hash
                  FROM ancestry section
                  JOIN solution_generation_evidence_source source
                    ON source.attempt_id = section.attempt_id
                 WHERE section.kind = 'MODEL_DRAFT'
                 ORDER BY source.evidence_order
                """, sectionVersionId);
    }

    private List<GenerationEvidenceSource> query(String sql, UUID id) {
        try {
            return jdbc.sql(sql).param("id", id).query((row, ignored) ->
                    new GenerationEvidenceSource(row.getInt("evidence_order"),
                            row.getObject("evidence_id", UUID.class),
                            row.getObject("space_id", UUID.class),
                            row.getObject("document_id", UUID.class),
                            row.getObject("version_id", UUID.class),
                            row.getString("node_id"), row.getString("excerpt_hash").strip())).list();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }
}
