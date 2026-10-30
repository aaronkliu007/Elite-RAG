package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.solution.proposal.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

public final class JdbcSolutionProposalEvidenceStore implements ProposalEvidenceStore {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    public JdbcSolutionProposalEvidenceStore(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
    }

    @Override public void save(UUID attemptId, ProposalEvidenceSnapshot snapshot) {
        Objects.requireNonNull(attemptId); Objects.requireNonNull(snapshot);
        try {
            transactions.executeWithoutResult(status -> {
                var exists = jdbc.sql("""
                        SELECT a.id FROM solution_model_proposal_attempt a
                        JOIN solution s ON s.id = a.solution_id
                        WHERE a.id = :id AND s.space_id = :space AND a.kind = 'DOCUMENT_REQ' FOR UPDATE OF a
                        """).param("id", attemptId).param("space", snapshot.spaceId()).query(UUID.class).optional();
                if (exists.isEmpty()) throw new ProposalRequestConflictException();
                boolean live = jdbc.sql("""
                        SELECT status = 'RUNNING' AND lease_until > clock_timestamp()
                        FROM solution_model_proposal_attempt WHERE id = :id
                        """).param("id", attemptId).query(Boolean.class).single();
                if (!live) throw new ProposalRequestConflictException();
                var old = find(attemptId);
                if (old.isPresent()) {
                    if (!old.orElseThrow().equals(snapshot)) throw new ProposalRequestConflictException();
                    requireLive(attemptId);
                    return;
                }
                jdbc.sql("INSERT INTO solution_proposal_snapshot(attempt_id, space_id) VALUES (:id, :space)")
                        .param("id", attemptId).param("space", snapshot.spaceId()).update();
                for (int i = 0; i < snapshot.documents().size(); i++) {
                    var document = snapshot.documents().get(i);
                    jdbc.sql("""
                            INSERT INTO solution_proposal_document_snapshot(attempt_id, document_order, space_id, document_id, version_id)
                            VALUES (:id, :position, :space, :document, :version)
                            """).param("id", attemptId).param("position", i + 1).param("space", snapshot.spaceId())
                            .param("document", document.documentId()).param("version", document.versionId()).update();
                }
                for (var source : snapshot.sources()) {
                    jdbc.sql("""
                            INSERT INTO solution_proposal_evidence_snapshot
                                (attempt_id, evidence_order, evidence_id, document_id, version_id, node_id, locator, excerpt_hash)
                            VALUES (:id, :position, :evidence, :document, :version, :node, CAST(:locator AS jsonb), :hash)
                            """).param("id", attemptId).param("position", source.position()).param("evidence", source.evidenceId())
                            .param("document", source.document().documentId()).param("version", source.document().versionId())
                            .param("node", source.nodeId()).param("locator", SolutionLocatorJson.write(source.locator()))
                            .param("hash", source.excerptHash()).update();
                }
                requireLive(attemptId);
            });
        } catch (DataAccessException failure) { throw PostgresPersistenceExceptionTranslator.translate(failure); }
    }

    private void requireLive(UUID attemptId) {
        boolean live = jdbc.sql("""
                SELECT status = 'RUNNING' AND lease_until > clock_timestamp()
                FROM solution_model_proposal_attempt WHERE id = :id
                """).param("id", attemptId).query(Boolean.class).single();
        if (!live) throw new ProposalRequestConflictException();
    }

    @Override public Optional<ProposalEvidenceSnapshot> find(UUID attemptId) {
        Objects.requireNonNull(attemptId);
        try {
            var space = jdbc.sql("SELECT space_id FROM solution_proposal_snapshot WHERE attempt_id = :id")
                    .param("id", attemptId).query(UUID.class).optional();
            if (space.isEmpty()) return Optional.empty();
            var documents = jdbc.sql("""
                    SELECT document_id, version_id FROM solution_proposal_document_snapshot
                    WHERE attempt_id = :id ORDER BY document_order
                    """).param("id", attemptId).query((row, number) -> new DocumentVersionRef(
                    row.getObject("document_id", UUID.class), row.getObject("version_id", UUID.class))).list();
            var sources = jdbc.sql("""
                    SELECT evidence_order, evidence_id, document_id, version_id, node_id, locator::text, excerpt_hash
                    FROM solution_proposal_evidence_snapshot WHERE attempt_id = :id ORDER BY evidence_order
                    """).param("id", attemptId).query((row, number) -> new ProposalEvidenceSnapshot.Source(
                    row.getInt("evidence_order"), row.getObject("evidence_id", UUID.class),
                    new DocumentVersionRef(row.getObject("document_id", UUID.class), row.getObject("version_id", UUID.class)),
                    row.getString("node_id"), SolutionLocatorJson.read(row.getString("locator")),
                    row.getString("excerpt_hash").strip())).list();
            return Optional.of(new ProposalEvidenceSnapshot(space.orElseThrow(), documents, sources));
        } catch (DataAccessException failure) { throw PostgresPersistenceExceptionTranslator.translate(failure); }
    }
}
