package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.solution.domain.SectionVersion;
import com.yunjihai.rag.solution.generation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcSectionGenerationCommitPort implements SectionGenerationCommitPort {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final JdbcSectionVersionRepository sections;
    private final GenerationAttemptStore attempts;

    public JdbcSectionGenerationCommitPort(JdbcClient jdbc, PlatformTransactionManager manager,
            JdbcSectionVersionRepository sections) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.sections = Objects.requireNonNull(sections);
        this.attempts = new JdbcGenerationAttemptStore(jdbc);
    }

    @Override
    public boolean commitIfCurrent(GenerationKey key, UUID attemptId, SectionVersion version,
            GenerationAttempt succeeded, long expectedAttemptRevision) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(attemptId);
        Objects.requireNonNull(version);
        Objects.requireNonNull(succeeded);
        if (!succeeded.id().equals(attemptId) || !succeeded.key().equals(key)
                || succeeded.status() != GenerationStatus.SUCCEEDED
                || succeeded.revision() != expectedAttemptRevision + 1
                || !succeeded.resultSectionVersionId().equals(Optional.of(version.id()))
                || !version.solutionId().equals(key.solutionId())
                || !version.sectionId().equals(key.sectionId())
                || !version.requirementVersionId().equals(key.requirementVersionId())
                || !version.outlineVersionId().equals(key.outlineVersionId())
                || !version.baselineSectionVersionId().equals(key.baselineSectionVersionId())) {
            throw new IllegalArgumentException("generation commit input mismatch");
        }
        try {
            return Boolean.TRUE.equals(transactions.execute(status -> {
                var solution = jdbc.sql("""
                        SELECT current_requirement_version_id, current_outline_version_id,
                               requirement_confirmed_by, outline_confirmed_by, phase
                        FROM solution WHERE id = :solutionId FOR UPDATE
                        """).param("solutionId", key.solutionId()).query((row, ignored) -> new CurrentSolution(
                                row.getObject("current_requirement_version_id", UUID.class),
                                row.getObject("current_outline_version_id", UUID.class),
                                row.getObject("requirement_confirmed_by", UUID.class) != null,
                                row.getObject("outline_confirmed_by", UUID.class) != null,
                                row.getString("phase"))).optional();
                if (solution.isEmpty() || !solution.orElseThrow().matches(key)) return false;
                var baseline = jdbc.sql("""
                        SELECT section_version_id FROM solution_adopted_section
                        WHERE solution_id = :solutionId AND section_id = :sectionId
                        """).param("solutionId", key.solutionId()).param("sectionId", key.sectionId())
                        .query(UUID.class).optional();
                if (!baseline.equals(key.baselineSectionVersionId())) return false;
                var attempt = jdbc.sql("""
                        SELECT id, client_key, input_fingerprint, status, revision
                        FROM solution_generation_attempt WHERE id = :attemptId FOR UPDATE
                        """).param("attemptId", attemptId).query((row, ignored) -> new CurrentAttempt(
                                row.getObject("id", UUID.class), row.getString("client_key"),
                                row.getString("input_fingerprint"), row.getString("status"),
                                row.getLong("revision"))).optional();
                if (attempt.isEmpty() || !attempt.orElseThrow().matches(key, expectedAttemptRevision)) {
                    return false;
                }
                sections.insertDraft(attemptId, version);
                attempts.replace(succeeded, expectedAttemptRevision);
                return true;
            }));
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private record CurrentSolution(UUID requirementId, UUID outlineId,
            boolean requirementsConfirmed, boolean outlineConfirmed, String phase) {
        boolean matches(GenerationKey key) {
            return requirementId.equals(key.requirementVersionId())
                    && key.outlineVersionId().equals(outlineId)
                    && requirementsConfirmed && outlineConfirmed
                    && !"FINALIZED".equals(phase);
        }
    }

    private record CurrentAttempt(UUID id, String clientKey, String fingerprint,
            String status, long revision) {
        boolean matches(GenerationKey key, long expectedRevision) {
            return key.clientKey().equals(clientKey)
                    && key.inputFingerprint().equals(fingerprint)
                    && "VERIFYING".equals(status)
                    && revision == expectedRevision;
        }
    }
}
