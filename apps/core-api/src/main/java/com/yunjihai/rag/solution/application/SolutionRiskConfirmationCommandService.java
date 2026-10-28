package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.solution.finalization.SolutionFinalizationService;
import com.yunjihai.rag.solution.verification.RiskConfirmation;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Makes confirmation persistence and its success audit indivisible. */
public final class SolutionRiskConfirmationCommandService {
    private final SolutionFinalizationService finalization;
    private final TransactionTemplate transactions;
    private final AuditSink audit;

    public SolutionRiskConfirmationCommandService(SolutionFinalizationService finalization,
            TransactionTemplate transactions, AuditSink audit) {
        this.finalization = Objects.requireNonNull(finalization);
        this.transactions = Objects.requireNonNull(transactions);
        this.audit = Objects.requireNonNull(audit);
    }

    public RiskConfirmation confirm(UUID actorId, UUID solutionId, UUID sectionVersionId,
            UUID claimId, String reason, Instant at) {
        try {
            return transactions.execute(status -> finalization.confirmRisk(actorId, solutionId,
                    sectionVersionId, claimId, reason, at));
        } catch (RuntimeException failure) {
            try {
                audit.record(new AuditCommand(actorId, AuditAction.RISK_CONFIRM, "SOLUTION", solutionId,
                        AuditOutcome.FAILED, Map.of("solutionId", solutionId.toString(),
                                "sectionVersionId", sectionVersionId.toString(),
                                "claimId", claimId.toString(), "status", "FAILED",
                                "errorCode", "RISK_CONFIRMATION_FAILED")));
            } catch (RuntimeException auditFailure) {
                failure.addSuppressed(auditFailure);
            }
            throw failure;
        }
    }
}
