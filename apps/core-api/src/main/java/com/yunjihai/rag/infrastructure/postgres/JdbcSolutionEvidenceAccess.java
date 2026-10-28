package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.solution.application.SolutionDocumentSourcePort;
import com.yunjihai.rag.solution.finalization.EvidenceAccessPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Resolves stored evidence identities and rechecks current publication and document ACL. */
public final class JdbcSolutionEvidenceAccess implements EvidenceAccessPort {
    private final JdbcClient jdbc;
    private final SolutionDocumentSourcePort documents;

    public JdbcSolutionEvidenceAccess(JdbcClient jdbc, SolutionDocumentSourcePort documents) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.documents = Objects.requireNonNull(documents);
    }

    @Override public Set<UUID> authorize(UUID actorId, UUID spaceId, Set<UUID> evidenceIds, Instant at) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(evidenceIds);
        Objects.requireNonNull(at);
        if (evidenceIds.isEmpty()) return Set.of();
        try {
            var rows = jdbc.sql("""
                    SELECT evidence_id, space_id, document_id, version_id
                    FROM solution_generation_evidence_source WHERE evidence_id IN (:ids)
                    """).param("ids", evidenceIds).query((row, ignored) -> new Source(
                            row.getObject("evidence_id", UUID.class), row.getObject("space_id", UUID.class),
                            row.getObject("document_id", UUID.class), row.getObject("version_id", UUID.class))).list();
            var allowed = new HashSet<UUID>();
            for (var row : rows) {
                if (!spaceId.equals(row.spaceId()) || !documents.isCurrentPublishedAndReadable(
                        actorId, spaceId, row.documentId(), row.versionId(), at)) {
                    return Set.of();
                }
                allowed.add(row.evidenceId());
            }
            return allowed.equals(evidenceIds) ? Set.copyOf(allowed) : Set.of();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private record Source(UUID evidenceId, UUID spaceId, UUID documentId, UUID versionId) { }
}
