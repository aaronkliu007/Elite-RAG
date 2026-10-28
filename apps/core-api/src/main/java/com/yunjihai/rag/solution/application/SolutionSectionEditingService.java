package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.audit.*;
import com.yunjihai.rag.solution.SolutionAction;
import com.yunjihai.rag.solution.SolutionAuthorizationPort;
import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.generation.*;
import com.yunjihai.rag.solution.port.SolutionRepository;
import com.yunjihai.rag.solution.verification.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** Serializes human draft saves and removes inherited model verification. */
public final class SolutionSectionEditingService {
    private final SolutionRepository solutions;
    private final SectionVersionRepository sections;
    private final SectionEditVersionQueryPort versions;
    private final GenerationEvidenceStore evidence;
    private final GenerationEvidenceAuthorizationPort evidenceAuthorization;
    private final SolutionAuthorizationPort authorization;
    private final TransactionTemplate transactions;
    private final AuditSink audit;
    private final Clock clock;

    public SolutionSectionEditingService(SolutionRepository solutions, SectionVersionRepository sections,
            SectionEditVersionQueryPort versions, GenerationEvidenceStore evidence,
            GenerationEvidenceAuthorizationPort evidenceAuthorization,
            SolutionAuthorizationPort authorization, TransactionTemplate transactions,
            AuditSink audit, Clock clock) {
        this.solutions = Objects.requireNonNull(solutions);
        this.sections = Objects.requireNonNull(sections);
        this.versions = Objects.requireNonNull(versions);
        this.evidence = Objects.requireNonNull(evidence);
        this.evidenceAuthorization = Objects.requireNonNull(evidenceAuthorization);
        this.authorization = Objects.requireNonNull(authorization);
        this.transactions = Objects.requireNonNull(transactions);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    public SectionVersion save(UUID actorId, UUID solutionId, UUID sectionId, UUID baselineId,
            UUID expectedDraftId, long expectedRevision, String content, String clientKey) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(solutionId);
        Objects.requireNonNull(sectionId);
        Objects.requireNonNull(baselineId);
        if (expectedRevision < 0 || clientKey == null || clientKey.isBlank() || clientKey.length() > 200
                || content == null || content.isBlank() || content.length() > 50_000) {
            throw new IllegalArgumentException("invalid section edit request");
        }
        var body = content.strip();
        var stableId = UUID.nameUUIDFromBytes(("SECTION_EDIT:" + actorId + ":" + solutionId
                + ":" + sectionId + ":" + clientKey).getBytes(StandardCharsets.UTF_8));
        try {
            return transactions.execute(status -> {
                var initial = solutions.findById(solutionId).orElseThrow(AccessDeniedAsNotFoundException::new);
                authorization.require(actorId, initial.spaceId(), solutionId,
                        SolutionAction.ADOPT_SECTION, clock.instant());
                solutions.lockById(solutionId);
                var current = solutions.findById(solutionId).orElseThrow(AccessDeniedAsNotFoundException::new);
                authorization.require(actorId, current.spaceId(), solutionId,
                        SolutionAction.ADOPT_SECTION, clock.instant());
                if (current.phase() == SolutionPhase.FINALIZED) {
                    throw new SolutionLifecycleException("SOLUTION_FINALIZED");
                }
                if (!baselineId.equals(current.adoptedSectionVersionIds().get(sectionId))) {
                    throw new ConcurrentSolutionUpdateException();
                }
                var baseline = sections.findById(baselineId).orElseThrow(AccessDeniedAsNotFoundException::new);
                if (baseline.kind() != SectionVersionKind.ADOPTED
                        || !baseline.solutionId().equals(solutionId) || !baseline.sectionId().equals(sectionId)
                        || !baseline.requirementVersionId().equals(current.currentRequirementVersionId())
                        || !current.currentOutlineVersionId().equals(Optional.of(baseline.outlineVersionId()))) {
                    throw new AccessDeniedAsNotFoundException();
                }
                var sources = evidence.findBySectionVersionId(baselineId);
                var ids = new HashSet<UUID>();
                sources.forEach(source -> ids.add(source.evidenceId()));
                if (sources.isEmpty() || baseline.evidenceIds().isEmpty()
                        || !ids.containsAll(baseline.evidenceIds())) {
                    throw new AccessDeniedAsNotFoundException();
                }
                evidenceAuthorization.requireReadable(actorId, current.spaceId(), sources, clock.instant());
                var existing = sections.findById(stableId);
                if (existing.isPresent()) {
                    var saved = existing.orElseThrow();
                    if (saved.kind() == SectionVersionKind.HUMAN_EDIT
                            && saved.solutionId().equals(solutionId) && saved.sectionId().equals(sectionId)
                            && saved.baselineSectionVersionId().equals(Optional.of(baselineId))
                            && saved.content().equals(body)
                            && saved.parentVersionId()
                                    .equals(Optional.of(expectedDraftId == null ? baselineId : expectedDraftId))) {
                        return saved;
                    }
                    throw new SolutionCommandConflictException("SECTION_EDIT_KEY_REUSED");
                }
                if (current.revision() != expectedRevision) throw new ConcurrentSolutionUpdateException();
                var latest = versions.latestEdit(solutionId, sectionId, baselineId);
                if (!Objects.equals(latest.map(SectionVersion::id).orElse(null), expectedDraftId)) {
                    throw new ConcurrentSolutionUpdateException();
                }
                if (body.equals(latest.map(SectionVersion::content).orElse(baseline.content()))) {
                    throw new IllegalArgumentException("unchanged section edit");
                }
                var claimId = UUID.nameUUIDFromBytes((stableId + ":claim").getBytes(StandardCharsets.UTF_8));
                var claim = new Claim(claimId, body, true, baseline.evidenceIds(),
                        baseline.claims().stream().flatMap(c -> c.riskTypes().stream())
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
                var candidate = new SectionVersion(stableId, solutionId, sectionId,
                        SectionVersionKind.HUMAN_EDIT, body, List.of(claim), baseline.evidenceIds(),
                        baseline.requirementVersionId(), baseline.outlineVersionId(),
                        Optional.of(baselineId), Optional.of(expectedDraftId == null ? baselineId : expectedDraftId),
                        Optional.of(actorId), Optional.empty(), clock.instant(),
                        new SolutionRiskReport(List.of(new ClaimVerification(claimId,
                                CitationVerdict.HUMAN_CONFIRMATION_REQUIRED, "HUMAN_EDIT_REVIEW_REQUIRED"))));
                sections.insertIfAbsent(candidate);
                audit.record(new AuditCommand(actorId, AuditAction.EDIT, "SOLUTION", solutionId,
                        AuditOutcome.SUCCEEDED, Map.of("solutionId", solutionId.toString(),
                                "sectionVersionId", stableId.toString(),
                                "status", "SUCCEEDED")));
                return candidate;
            });
        } catch (RuntimeException failure) {
            try {
                audit.record(new AuditCommand(actorId, AuditAction.EDIT, "SOLUTION", solutionId,
                        failure instanceof AccessDeniedAsNotFoundException || failure instanceof SecurityException
                                ? AuditOutcome.DENIED : AuditOutcome.FAILED,
                        Map.of("solutionId", solutionId.toString(),
                                "status", "FAILED", "errorCode", "SECTION_EDIT_FAILED")));
            } catch (RuntimeException auditFailure) { failure.addSuppressed(auditFailure); }
            throw failure;
        }
    }
}
