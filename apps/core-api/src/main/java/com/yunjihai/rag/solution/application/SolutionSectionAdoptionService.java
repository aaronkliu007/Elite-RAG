package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.solution.SolutionAction;
import com.yunjihai.rag.solution.SolutionAuthorizationPort;
import com.yunjihai.rag.solution.domain.Solution;
import com.yunjihai.rag.solution.port.SolutionRepository;
import com.yunjihai.rag.solution.generation.SectionVersionRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Runs the human adoption and its idempotency record in the same database transaction. */
public final class SolutionSectionAdoptionService implements SolutionSectionAdoptionPort {
    private final SolutionRepository solutions;
    private final SectionVersionRepository sections;
    private final SolutionAuthorizationPort authorization;
    private final SolutionCommandIdempotencyStore idempotency;
    private final TransactionTemplate transactions;
    private final Adoption adoption;
    private final AuditSink audit;
    private final Clock clock;

    public SolutionSectionAdoptionService(SolutionRepository solutions, SectionVersionRepository sections,
            SolutionAuthorizationPort authorization, SolutionCommandIdempotencyStore idempotency,
            TransactionTemplate transactions, Adoption adoption, AuditSink audit, Clock clock) {
        this.solutions = Objects.requireNonNull(solutions);
        this.sections = Objects.requireNonNull(sections);
        this.authorization = Objects.requireNonNull(authorization);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.transactions = Objects.requireNonNull(transactions);
        this.adoption = Objects.requireNonNull(adoption);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    public SolutionCommandResult adopt(UUID actorId, UUID solutionId, UUID sectionId,
            UUID sectionVersionId, long expectedRevision, String clientKey) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(solutionId);
        Objects.requireNonNull(sectionId);
        Objects.requireNonNull(sectionVersionId);
        if (expectedRevision < 0) throw new IllegalArgumentException("negative revision");
        var key = new SolutionCommandIdempotencyStore.Key(actorId, solutionId,
                "ADOPT_SECTION", clientKey);
        var digest = digest(solutionId, sectionId, sectionVersionId, expectedRevision);
        try {
            return transactions.execute(status -> {
                var current = solutions.findById(solutionId).orElseThrow(AccessDeniedAsNotFoundException::new);
                authorization.require(actorId, current.spaceId(), solutionId,
                        SolutionAction.ADOPT_SECTION, clock.instant());
                var candidate = sections.findById(sectionVersionId)
                        .orElseThrow(AccessDeniedAsNotFoundException::new);
                if (!candidate.solutionId().equals(solutionId) || !candidate.sectionId().equals(sectionId)) {
                    throw new AccessDeniedAsNotFoundException();
                }
                var replay = idempotency.claim(key, digest);
                if (replay.isPresent()) {
                    var result = replay.orElseThrow();
                    if (!result.solutionId().equals(solutionId)) throw new AccessDeniedAsNotFoundException();
                    return result;
                }
                solutions.lockById(solutionId);
                current = solutions.findById(solutionId).orElseThrow(AccessDeniedAsNotFoundException::new);
                authorization.require(actorId, current.spaceId(), solutionId,
                        SolutionAction.ADOPT_SECTION, clock.instant());
                var updated = adoption.adopt(actorId, solutionId, sectionVersionId,
                        expectedRevision, clock.instant());
                var result = SolutionCommandResult.from(updated);
                idempotency.complete(key, result);
                return result;
            });
        } catch (RuntimeException failure) {
            try {
                audit.record(new AuditCommand(actorId, AuditAction.SECTION_ADOPT,
                        "SOLUTION", solutionId,
                        failure instanceof AccessDeniedAsNotFoundException || failure instanceof SecurityException
                                ? AuditOutcome.DENIED : AuditOutcome.FAILED,
                        Map.of("solutionId", solutionId.toString(),
                                "sectionVersionId", sectionVersionId.toString(),
                                "status", "FAILED", "errorCode", "SECTION_ADOPTION_FAILED")));
            } catch (RuntimeException auditFailure) {
                failure.addSuppressed(auditFailure);
            }
            throw failure;
        }
    }

    private static String digest(UUID solutionId, UUID sectionId, UUID sectionVersionId,
            long expectedRevision) {
        try {
            var canonical = String.join("\n", solutionId.toString(), sectionId.toString(),
                    sectionVersionId.toString(), Long.toString(expectedRevision));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    @FunctionalInterface
    public interface Adoption {
        Solution adopt(UUID actorId, UUID solutionId, UUID sectionVersionId,
                long expectedRevision, Instant at);
    }
}
