package com.yunjihai.rag.solution.feedback;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.audit.*;
import com.yunjihai.rag.solution.application.SolutionAuthorityUnavailableException;
import com.yunjihai.rag.solution.application.SolutionCommandConflictException;
import com.yunjihai.rag.solution.application.SolutionSourceAccess;
import com.yunjihai.rag.solution.finalization.EvidenceAccessPort;
import com.yunjihai.rag.solution.finalization.FinalizedSolutionVersion;
import com.yunjihai.rag.solution.finalization.FinalizedSolutionVersionRepository;
import com.yunjihai.rag.solution.generation.SectionVersionRepository;
import com.yunjihai.rag.solution.port.RequirementVersionRepository;
import com.yunjihai.rag.solution.port.SolutionRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.*;

/** Immutable categorical feedback; source and evidence ACLs are checked on every read and write. */
public final class FeedbackService {
    private final SolutionRepository solutions;
    private final FinalizedSolutionVersionRepository finalized;
    private final RequirementVersionRepository requirements;
    private final SectionVersionRepository sections;
    private final SolutionSourceAccess sources;
    private final EvidenceAccessPort evidence;
    private final FeedbackStore store;
    private final TransactionTemplate transactions;
    private final AuditSink audit;
    private final Clock clock;

    public FeedbackService(SolutionRepository solutions, FinalizedSolutionVersionRepository finalized,
            RequirementVersionRepository requirements, SectionVersionRepository sections,
            SolutionSourceAccess sources, EvidenceAccessPort evidence, FeedbackStore store,
            TransactionTemplate transactions, AuditSink audit, Clock clock) {
        this.solutions = Objects.requireNonNull(solutions);
        this.finalized = Objects.requireNonNull(finalized);
        this.requirements = Objects.requireNonNull(requirements);
        this.sections = Objects.requireNonNull(sections);
        this.sources = Objects.requireNonNull(sources);
        this.evidence = Objects.requireNonNull(evidence);
        this.store = Objects.requireNonNull(store);
        this.transactions = Objects.requireNonNull(transactions);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    public SolutionFeedback submit(UUID actorId, UUID solutionId, UUID finalizedVersionId,
            UUID sectionVersionId, FeedbackReason reason, String clientKey) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(reason);
        if (clientKey == null || clientKey.isBlank() || clientKey.length() > 200) {
            throw new IllegalArgumentException("invalid feedback key");
        }
        try {
            var version = requireReadable(actorId, solutionId, finalizedVersionId);
            if (sectionVersionId != null && !version.sectionVersionIds().contains(sectionVersionId)) {
                throw new AccessDeniedAsNotFoundException();
            }
            return transactions.execute(status -> {
                var previous = store.findByKey(actorId, solutionId, clientKey);
                if (previous.isPresent()) return matching(previous.orElseThrow(), finalizedVersionId, sectionVersionId, reason);
                var value = new SolutionFeedback(UUID.randomUUID(), solutionId, finalizedVersionId,
                        sectionVersionId, actorId, reason, clientKey, clock.instant());
                var inserted = matching(store.insertIfAbsent(value), finalizedVersionId, sectionVersionId, reason);
                if (inserted.id().equals(value.id())) {
                    var metadata = new HashMap<String, String>();
                    metadata.put("solutionId", solutionId.toString());
                    metadata.put("finalVersionId", finalizedVersionId.toString());
                    if (sectionVersionId != null) metadata.put("sectionVersionId", sectionVersionId.toString());
                    audit.record(new AuditCommand(actorId, AuditAction.REVIEW, "SOLUTION", solutionId,
                            AuditOutcome.SUCCEEDED, metadata));
                }
                return inserted;
            });
        } catch (RuntimeException failure) {
            recordFailure(actorId, solutionId, finalizedVersionId, failure);
            throw failure;
        }
    }

    public List<SolutionFeedback> listMine(UUID actorId, UUID solutionId, UUID finalizedVersionId) {
        try {
            requireReadable(actorId, solutionId, finalizedVersionId);
            return store.listByActorAndVersion(actorId, finalizedVersionId);
        } catch (RuntimeException failure) {
            recordFailure(actorId, solutionId, finalizedVersionId, failure);
            throw failure;
        }
    }

    private void recordFailure(UUID actorId, UUID solutionId, UUID versionId, RuntimeException failure) {
        try {
            audit.record(new AuditCommand(actorId, AuditAction.REVIEW, "SOLUTION", solutionId,
                    failure instanceof AccessDeniedAsNotFoundException || failure instanceof SecurityException
                            ? AuditOutcome.DENIED : AuditOutcome.FAILED,
                    Map.of("solutionId", solutionId.toString(), "finalVersionId", versionId.toString(),
                            "status", "FAILED", "errorCode", "SOLUTION_FEEDBACK_FAILED")));
        } catch (RuntimeException auditFailure) { failure.addSuppressed(auditFailure); }
    }

    private FinalizedSolutionVersion requireReadable(UUID actorId, UUID solutionId, UUID versionId) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(solutionId);
        Objects.requireNonNull(versionId);
        var solution = solutions.findById(solutionId).orElseThrow(AccessDeniedAsNotFoundException::new);
        var version = finalized.findById(versionId).orElseThrow(AccessDeniedAsNotFoundException::new);
        if (!version.solutionId().equals(solutionId)
                || !solution.finalizedVersionId().equals(Optional.of(versionId))) {
            throw new AccessDeniedAsNotFoundException();
        }
        var requirement = requirements.findById(version.requirementVersionId())
                .orElseThrow(AccessDeniedAsNotFoundException::new);
        sources.requireCurrentSources(actorId, solution.spaceId(), requirement, clock.instant());
        var ids = new HashSet<UUID>();
        for (var sectionId : version.sectionVersionIds()) {
            var section = sections.findById(sectionId).orElseThrow(AccessDeniedAsNotFoundException::new);
            if (!section.solutionId().equals(solutionId)
                    || !section.requirementVersionId().equals(version.requirementVersionId())
                    || !section.outlineVersionId().equals(version.outlineVersionId())) {
                throw new AccessDeniedAsNotFoundException();
            }
            ids.addAll(section.evidenceIds());
        }
        if (ids.isEmpty()) throw new AccessDeniedAsNotFoundException();
        Set<UUID> allowed;
        try { allowed = evidence.authorize(actorId, solution.spaceId(), Set.copyOf(ids), clock.instant()); }
        catch (RuntimeException unavailable) { throw new SolutionAuthorityUnavailableException(); }
        if (allowed == null || !allowed.equals(ids)) throw new AccessDeniedAsNotFoundException();
        return version;
    }

    private static SolutionFeedback matching(SolutionFeedback saved, UUID versionId,
            UUID sectionVersionId, FeedbackReason reason) {
        if (!saved.finalizedVersionId().equals(versionId)
                || !Objects.equals(saved.sectionVersionId(), sectionVersionId)
                || saved.reason() != reason) {
            throw new SolutionCommandConflictException("SOLUTION_FEEDBACK_KEY_REUSED");
        }
        return saved;
    }
}
