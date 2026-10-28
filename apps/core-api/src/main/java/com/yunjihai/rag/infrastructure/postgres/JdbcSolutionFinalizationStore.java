package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.solution.domain.ConcurrentSolutionUpdateException;
import com.yunjihai.rag.solution.domain.Solution;
import com.yunjihai.rag.solution.finalization.FinalizedSolutionVersion;
import com.yunjihai.rag.solution.finalization.FinalizedSolutionVersionRepository;
import com.yunjihai.rag.solution.finalization.SolutionFinalizationStore;
import com.yunjihai.rag.solution.port.SolutionRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** The final record, current solution pointer, and success audit commit together. */
public final class JdbcSolutionFinalizationStore implements SolutionFinalizationStore, FinalizedSolutionVersionRepository {
    private final JdbcClient jdbc;
    private final SolutionRepository solutions;
    private final AuditSink audit;
    private final TransactionTemplate transactions;

    public JdbcSolutionFinalizationStore(JdbcClient jdbc, SolutionRepository solutions,
            AuditSink audit, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.solutions = Objects.requireNonNull(solutions);
        this.audit = Objects.requireNonNull(audit);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
    }

    @Override public void commit(Solution updated, long expectedRevision,
            FinalizedSolutionVersion finalized, AuditCommand successAudit) {
        Objects.requireNonNull(updated);
        Objects.requireNonNull(finalized);
        Objects.requireNonNull(successAudit);
        if (!updated.id().equals(finalized.solutionId())
                || !updated.finalizedVersionId().equals(Optional.of(finalized.id()))) {
            throw new IllegalArgumentException("finalized version does not match solution");
        }
        try {
            transactions.executeWithoutResult(status -> {
                solutions.lockById(updated.id());
                var current = solutions.findById(updated.id())
                        .orElseThrow(ConcurrentSolutionUpdateException::new);
                if (current.revision() != expectedRevision
                        || !current.currentRequirementVersionId().equals(finalized.requirementVersionId())
                        || !current.currentOutlineVersionId().equals(Optional.of(finalized.outlineVersionId()))
                        || !current.adoptedSectionVersionIds().equals(updated.adoptedSectionVersionIds())) {
                    throw new ConcurrentSolutionUpdateException();
                }
                jdbc.sql("""
                        INSERT INTO solution_finalized_version
                            (id, solution_id, solution_revision, requirement_version_id,
                             outline_version_id, created_by, created_at, content_hash)
                        VALUES (:id, :solution, :revision, :requirement, :outline, :actor, :at, :hash)
                        """).param("id", finalized.id()).param("solution", finalized.solutionId())
                        .param("revision", finalized.solutionRevision())
                        .param("requirement", finalized.requirementVersionId())
                        .param("outline", finalized.outlineVersionId())
                        .param("actor", finalized.createdBy())
                        .param("at", OffsetDateTime.ofInstant(finalized.createdAt(), ZoneOffset.UTC))
                        .param("hash", finalized.contentHash()).update();
                for (int i = 0; i < finalized.sectionVersionIds().size(); i++) {
                    jdbc.sql("""
                            INSERT INTO solution_finalized_section
                                (finalized_version_id, section_order, section_version_id)
                            VALUES (:id, :position, :section)
                            """).param("id", finalized.id()).param("position", i + 1)
                            .param("section", finalized.sectionVersionIds().get(i)).update();
                }
                for (var confirmationId : finalized.riskConfirmationIds()) {
                    jdbc.sql("""
                            INSERT INTO solution_finalized_risk_confirmation (finalized_version_id, confirmation_id)
                            VALUES (:id, :confirmation)
                            """).param("id", finalized.id()).param("confirmation", confirmationId).update();
                }
                solutions.replace(updated, expectedRevision);
                audit.record(successAudit);
            });
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override public Optional<FinalizedSolutionVersion> findById(UUID versionId) {
        Objects.requireNonNull(versionId);
        try {
            return jdbc.sql("SELECT * FROM solution_finalized_version WHERE id = :id")
                    .param("id", versionId).query((row, ignored) -> new FinalizedSolutionVersion(
                            row.getObject("id", UUID.class), row.getObject("solution_id", UUID.class),
                            row.getLong("solution_revision"), row.getObject("requirement_version_id", UUID.class),
                            row.getObject("outline_version_id", UUID.class),
                            sectionIds(versionId), confirmationIds(versionId),
                            row.getObject("created_by", UUID.class),
                            row.getObject("created_at", OffsetDateTime.class).toInstant(),
                            row.getString("content_hash").strip())).optional();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private List<UUID> sectionIds(UUID versionId) {
        return jdbc.sql("""
                SELECT section_version_id FROM solution_finalized_section
                WHERE finalized_version_id = :id ORDER BY section_order
                """).param("id", versionId).query(UUID.class).list();
    }

    private List<UUID> confirmationIds(UUID versionId) {
        return jdbc.sql("""
                SELECT confirmation_id FROM solution_finalized_risk_confirmation
                WHERE finalized_version_id = :id ORDER BY confirmation_id
                """).param("id", versionId).query(UUID.class).list();
    }
}
