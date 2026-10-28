package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.solution.verification.RiskConfirmation;
import com.yunjihai.rag.solution.verification.RiskConfirmationRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class JdbcRiskConfirmationRepository implements RiskConfirmationRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcRiskConfirmationRepository(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
    }

    @Override public List<RiskConfirmation> findBySolutionId(UUID solutionId) {
        Objects.requireNonNull(solutionId);
        try {
            return jdbc.sql("SELECT * FROM solution_risk_confirmation WHERE solution_id = :solution ORDER BY confirmed_at, id")
                    .param("solution", solutionId).query((row, ignored) -> new RiskConfirmation(
                            row.getObject("id", UUID.class), row.getObject("solution_id", UUID.class),
                            row.getObject("section_version_id", UUID.class), row.getObject("claim_id", UUID.class),
                            evidence(row.getObject("id", UUID.class)),
                            row.getObject("requirement_version_id", UUID.class),
                            row.getObject("outline_version_id", UUID.class),
                            row.getString("claim_fingerprint").strip(),
                            row.getObject("confirmed_by", UUID.class), row.getString("reason"),
                            row.getObject("confirmed_at", OffsetDateTime.class).toInstant())).list();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override public RiskConfirmation insertIfAbsent(RiskConfirmation confirmation) {
        Objects.requireNonNull(confirmation);
        try {
            return transactions.execute(status -> {
                var inserted = jdbc.sql("""
                        INSERT INTO solution_risk_confirmation
                            (id, solution_id, section_version_id, claim_id, requirement_version_id,
                             outline_version_id, claim_fingerprint, confirmed_by, reason, confirmed_at)
                        VALUES (:id, :solution, :section, :claim, :requirement, :outline,
                                :fingerprint, :actor, :reason, :at)
                        ON CONFLICT (section_version_id, claim_id, claim_fingerprint) DO NOTHING
                        RETURNING id
                        """).param("id", confirmation.id()).param("solution", confirmation.solutionId())
                        .param("section", confirmation.sectionVersionId()).param("claim", confirmation.claimId())
                        .param("requirement", confirmation.requirementVersionId())
                        .param("outline", confirmation.outlineVersionId())
                        .param("fingerprint", confirmation.claimFingerprint())
                        .param("actor", confirmation.confirmedBy()).param("reason", confirmation.reason())
                        .param("at", OffsetDateTime.ofInstant(confirmation.confirmedAt(), ZoneOffset.UTC))
                        .query(UUID.class).optional();
                if (inserted.isPresent()) {
                    for (var evidenceId : confirmation.evidenceIds()) {
                        jdbc.sql("INSERT INTO solution_risk_confirmation_evidence (confirmation_id, evidence_id) VALUES (:id, :evidence)")
                                .param("id", confirmation.id()).param("evidence", evidenceId).update();
                    }
                    return confirmation;
                }
                return findBySolutionId(confirmation.solutionId()).stream()
                        .filter(existing -> existing.bindingKey().equals(confirmation.bindingKey()))
                        .findFirst().orElseThrow(() -> new IllegalStateException("RISK_CONFIRMATION_CONFLICT"));
            });
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private Set<UUID> evidence(UUID confirmationId) {
        return Set.copyOf(jdbc.sql("SELECT evidence_id FROM solution_risk_confirmation_evidence WHERE confirmation_id = :id")
                .param("id", confirmationId).query(UUID.class).list());
    }
}
