package com.yunjihai.rag.solution.finalization;

import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.common.UuidV7;
import com.yunjihai.rag.solution.SolutionAction;
import com.yunjihai.rag.solution.SolutionAuthorizationPort;
import com.yunjihai.rag.solution.domain.SectionVersionKind;
import com.yunjihai.rag.solution.domain.SolutionLifecycleException;
import com.yunjihai.rag.solution.generation.SectionVersionRepository;
import com.yunjihai.rag.solution.port.OutlineVersionRepository;
import com.yunjihai.rag.solution.port.SolutionRepository;
import com.yunjihai.rag.solution.verification.CitationVerdict;
import com.yunjihai.rag.solution.verification.RiskConfirmation;
import com.yunjihai.rag.solution.verification.RiskConfirmationRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class SolutionFinalizationService {

    private final SolutionRepository solutions;
    private final OutlineVersionRepository outlines;
    private final SectionVersionRepository sections;
    private final RiskConfirmationRepository confirmations;
    private final SolutionAuthorizationPort authorization;
    private final EvidenceAccessPort evidenceAccess;
    private final SolutionFinalizationStore finalizationStore;
    private final AuditSink audit;

    public SolutionFinalizationService(
            SolutionRepository solutions,
            OutlineVersionRepository outlines,
            SectionVersionRepository sections,
            RiskConfirmationRepository confirmations,
            SolutionAuthorizationPort authorization,
            EvidenceAccessPort evidenceAccess,
            SolutionFinalizationStore finalizationStore,
            AuditSink audit) {
        this.solutions = Objects.requireNonNull(solutions, "solutions");
        this.outlines = Objects.requireNonNull(outlines, "outlines");
        this.sections = Objects.requireNonNull(sections, "sections");
        this.confirmations = Objects.requireNonNull(confirmations, "confirmations");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.evidenceAccess = Objects.requireNonNull(evidenceAccess, "evidenceAccess");
        this.finalizationStore = Objects.requireNonNull(finalizationStore, "finalizationStore");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public RiskConfirmation confirmRisk(
            UUID actorId,
            UUID solutionId,
            UUID sectionVersionId,
            UUID claimId,
            String reason,
            Instant at) {
        try {
            return confirmRiskInternal(
                    actorId, solutionId, sectionVersionId, claimId, reason, at);
        } catch (RuntimeException failure) {
            recordFailure(actorId, solutionId, AuditAction.RISK_CONFIRM,
                    "RISK_CONFIRMATION_FAILED", failure);
            throw failure;
        }
    }

    private RiskConfirmation confirmRiskInternal(
            UUID actorId,
            UUID solutionId,
            UUID sectionVersionId,
            UUID claimId,
            String reason,
            Instant at) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(solutionId, "solutionId");
        Objects.requireNonNull(sectionVersionId, "sectionVersionId");
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(at, "at");
        var solution = solutions.findById(solutionId)
                .orElseThrow(() -> new SolutionLifecycleException("SOLUTION_NOT_FOUND"));
        authorization.require(actorId, solution.spaceId(), solutionId, SolutionAction.CONFIRM_RISK, at);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("risk confirmation reason is invalid");
        }
        var section = sections.findById(sectionVersionId)
                .orElseThrow(() -> new SolutionLifecycleException("SECTION_VERSION_NOT_FOUND"));
        if (section.kind() != SectionVersionKind.ADOPTED
                || !solutionId.equals(section.solutionId())
                || !sectionVersionId.equals(solution.adoptedSectionVersionIds().get(section.sectionId()))
                || !solution.currentRequirementVersionId().equals(section.requirementVersionId())
                || solution.currentOutlineVersionId().isEmpty()
                || !solution.currentOutlineVersionId().orElseThrow().equals(section.outlineVersionId())) {
            throw new SolutionLifecycleException("RISK_CONFIRMATION_TARGET_NOT_CURRENT");
        }
        var claim = section.claims().stream()
                .filter(candidate -> candidate.claimId().equals(claimId))
                .findFirst()
                .orElseThrow(() -> new SolutionLifecycleException("CLAIM_NOT_FOUND"));
        var verdict = section.verification().verifications().stream()
                .filter(result -> result.claimId().equals(claimId))
                .findFirst()
                .orElseThrow(() -> new SolutionLifecycleException("CLAIM_NOT_VERIFIED"));
        if (verdict.verdict() != CitationVerdict.HUMAN_CONFIRMATION_REQUIRED) {
            throw new SolutionLifecycleException("RISK_CONFIRMATION_NOT_ALLOWED");
        }
        var allowedEvidence = evidenceAccess.authorize(actorId, solution.spaceId(), claim.evidenceIds(), at);
        if (allowedEvidence == null || !allowedEvidence.equals(claim.evidenceIds())) {
            throw new SolutionLifecycleException("EVIDENCE_ACCESS_DENIED");
        }
        var confirmation = RiskConfirmation.create(
                UuidV7.generate(at), solutionId, section, claim, actorId, reason, at);
        var stored = confirmations.insertIfAbsent(confirmation);
        audit.record(new AuditCommand(
                actorId, AuditAction.RISK_CONFIRM, "SOLUTION", solutionId, AuditOutcome.SUCCEEDED,
                Map.of(
                        "solutionId", solutionId.toString(),
                        "sectionVersionId", sectionVersionId.toString(),
                        "claimId", claimId.toString(),
                        "evidenceCount", Integer.toString(claim.evidenceIds().size()),
                        "status", "SUCCEEDED")));
        return stored;
    }

    public FinalizedSolutionVersion finalizeSolution(
            UUID actorId,
            UUID solutionId,
            long expectedRevision,
            Instant at) {
        try {
            return finalizeSolutionInternal(actorId, solutionId, expectedRevision, at);
        } catch (RuntimeException failure) {
            recordFailure(actorId, solutionId, AuditAction.SOLUTION_FINALIZE,
                    "SOLUTION_FINALIZATION_FAILED", failure);
            throw failure;
        }
    }

    private FinalizedSolutionVersion finalizeSolutionInternal(
            UUID actorId,
            UUID solutionId,
            long expectedRevision,
            Instant at) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(solutionId, "solutionId");
        Objects.requireNonNull(at, "at");
        var solution = solutions.findById(solutionId)
                .orElseThrow(() -> new SolutionLifecycleException("SOLUTION_NOT_FOUND"));
        authorization.require(actorId, solution.spaceId(), solutionId, SolutionAction.FINALIZE, at);
        if (solution.revision() != expectedRevision) {
            throw new com.yunjihai.rag.solution.domain.ConcurrentSolutionUpdateException();
        }
        if (solution.requirementConfirmation().isEmpty()
                || !solution.requirementConfirmation().orElseThrow().versionId()
                .equals(solution.currentRequirementVersionId())) {
            throw new SolutionLifecycleException("REQUIREMENTS_NOT_CONFIRMED");
        }
        var outlineId = solution.currentOutlineVersionId()
                .orElseThrow(() -> new SolutionLifecycleException("OUTLINE_NOT_CONFIRMED"));
        if (solution.outlineConfirmation().isEmpty()
                || !solution.outlineConfirmation().orElseThrow().versionId().equals(outlineId)) {
            throw new SolutionLifecycleException("OUTLINE_NOT_CONFIRMED");
        }
        var outline = outlines.findById(outlineId)
                .orElseThrow(() -> new SolutionLifecycleException("OUTLINE_VERSION_NOT_FOUND"));
        var allConfirmations = List.copyOf(confirmations.findBySolutionId(solutionId));
        var includedConfirmations = new ArrayList<RiskConfirmation>();
        var selectedSections = new ArrayList<com.yunjihai.rag.solution.domain.SectionVersion>();
        var evidenceIds = new HashSet<UUID>();

        for (var node : outline.nodes()) {
            var sectionVersionId = solution.adoptedSectionVersionIds().get(node.sectionId());
            if (sectionVersionId == null) {
                if (node.required()) {
                    throw new SolutionLifecycleException("REQUIRED_SECTION_NOT_ADOPTED");
                }
                continue;
            }
            var section = sections.findById(sectionVersionId)
                    .orElseThrow(() -> new SolutionLifecycleException("SECTION_VERSION_NOT_FOUND"));
            if (section.kind() != SectionVersionKind.ADOPTED
                    || !section.solutionId().equals(solutionId)
                    || !section.sectionId().equals(node.sectionId())
                    || !section.requirementVersionId().equals(solution.currentRequirementVersionId())
                    || !section.outlineVersionId().equals(outlineId)) {
                throw new SolutionLifecycleException("ADOPTED_SECTION_NOT_CURRENT");
            }
            if (section.verification().hasBlockingVerdict()) {
                throw new SolutionLifecycleException("SECTION_VERIFICATION_BLOCKING");
            }
            for (var verification : section.verification().verifications()) {
                if (verification.verdict() == CitationVerdict.HUMAN_CONFIRMATION_REQUIRED) {
                    var confirmation = allConfirmations.stream()
                            .filter(candidate -> candidate.claimId().equals(verification.claimId()))
                            .filter(candidate -> candidate.matches(section))
                            .min(Comparator.comparing(candidate -> candidate.id().toString()))
                            .orElseThrow(() -> new SolutionLifecycleException("RISK_CONFIRMATION_REQUIRED"));
                    includedConfirmations.add(confirmation);
                }
            }
            selectedSections.add(section);
            evidenceIds.addAll(section.evidenceIds());
        }

        var requestedEvidence = Set.copyOf(evidenceIds);
        Set<UUID> allowedEvidence;
        try {
            allowedEvidence = evidenceAccess.authorize(
                    actorId, solution.spaceId(), requestedEvidence, at);
        } catch (RuntimeException unavailable) {
            throw new SolutionLifecycleException("EVIDENCE_ACCESS_REVIEW_FAILED");
        }
        if (allowedEvidence == null || !Set.copyOf(allowedEvidence).equals(requestedEvidence)) {
            throw new SolutionLifecycleException("EVIDENCE_ACCESS_DENIED");
        }
        var current = solutions.findById(solutionId)
                .orElseThrow(() -> new SolutionLifecycleException("SOLUTION_NOT_FOUND"));
        if (current.revision() != expectedRevision
                || !current.currentRequirementVersionId().equals(solution.currentRequirementVersionId())
                || !current.currentOutlineVersionId().equals(solution.currentOutlineVersionId())
                || !current.adoptedSectionVersionIds().equals(solution.adoptedSectionVersionIds())) {
            throw new com.yunjihai.rag.solution.domain.ConcurrentSolutionUpdateException();
        }

        var finalVersionId = UuidV7.generate(at);
        var confirmationIds = includedConfirmations.stream()
                .map(RiskConfirmation::id)
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        var finalized = new FinalizedSolutionVersion(
                finalVersionId,
                solutionId,
                solution.solutionRevision(),
                solution.currentRequirementVersionId(),
                outlineId,
                selectedSections.stream().map(section -> section.id()).toList(),
                confirmationIds,
                actorId,
                at,
                contentHash(solution, outlineId, selectedSections, confirmationIds));
        var updated = solution.markFinalized(finalVersionId, expectedRevision);
        var successAudit = new AuditCommand(
                actorId,
                AuditAction.SOLUTION_FINALIZE,
                "SOLUTION",
                solutionId,
                AuditOutcome.SUCCEEDED,
                Map.of(
                        "solutionId", solutionId.toString(),
                        "finalVersionId", finalVersionId.toString(),
                        "versionNumber", Long.toString(solution.solutionRevision()),
                        "sectionCount", Integer.toString(selectedSections.size()),
                        "riskCount", Integer.toString(confirmationIds.size()),
                        "evidenceCount", Integer.toString(requestedEvidence.size()),
                        "contentHash", finalized.contentHash(),
                        "status", "FINALIZED"));
        finalizationStore.commit(updated, expectedRevision, finalized, successAudit);
        return finalized;
    }

    private void recordFailure(
            UUID actorId,
            UUID solutionId,
            AuditAction action,
            String fallbackCode,
            RuntimeException original) {
        try {
            var errorCode = original instanceof SolutionLifecycleException lifecycle
                    ? lifecycle.errorCode()
                    : fallbackCode;
            audit.record(new AuditCommand(
                    actorId,
                    action,
                    "SOLUTION",
                    solutionId,
                    AuditOutcome.FAILED,
                    Map.of(
                            "solutionId", solutionId.toString(),
                            "status", "FAILED",
                            "errorCode", errorCode)));
        } catch (RuntimeException auditFailure) {
            original.addSuppressed(auditFailure);
        }
    }

    private static String contentHash(
            com.yunjihai.rag.solution.domain.Solution solution,
            UUID outlineId,
            List<com.yunjihai.rag.solution.domain.SectionVersion> sections,
            List<UUID> confirmationIds) {
        var canonical = new StringBuilder()
                .append(solution.id()).append('\n')
                .append(solution.solutionRevision()).append('\n')
                .append(solution.currentRequirementVersionId()).append('\n')
                .append(outlineId).append('\n');
        for (var section : sections) {
            canonical.append(section.sectionId()).append('\n')
                    .append(section.id()).append('\n')
                    .append(section.content()).append('\n');
            for (var claim : section.claims()) {
                canonical.append(claim.claimId()).append('|')
                        .append(claim.text()).append('|')
                        .append(claim.factual()).append('|')
                        .append(claim.evidenceIds().stream().map(UUID::toString).sorted()
                                .collect(Collectors.joining(",")))
                        .append('|')
                        .append(claim.riskTypes().stream().map(Enum::name).sorted()
                                .collect(Collectors.joining(",")))
                        .append('\n');
            }
        }
        confirmationIds.forEach(id -> canonical.append(id).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
