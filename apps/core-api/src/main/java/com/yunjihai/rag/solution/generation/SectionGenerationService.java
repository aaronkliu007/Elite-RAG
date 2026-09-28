package com.yunjihai.rag.solution.generation;

import com.yunjihai.rag.common.UuidV7;
import com.yunjihai.rag.retrieval.RetrievalService;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.RetrievalQuery;
import com.yunjihai.rag.solution.SolutionAction;
import com.yunjihai.rag.solution.SolutionAuthorizationPort;
import com.yunjihai.rag.solution.domain.ConcurrentSolutionUpdateException;
import com.yunjihai.rag.solution.domain.OutlineNode;
import com.yunjihai.rag.solution.domain.OutlineVersion;
import com.yunjihai.rag.solution.domain.RequirementVersion;
import com.yunjihai.rag.solution.domain.SectionVersion;
import com.yunjihai.rag.solution.domain.SectionVersionKind;
import com.yunjihai.rag.solution.domain.Solution;
import com.yunjihai.rag.solution.domain.SolutionLifecycleException;
import com.yunjihai.rag.solution.model.GeneratedSectionDraft;
import com.yunjihai.rag.solution.model.ModelScenario;
import com.yunjihai.rag.solution.model.SolutionModelPort;
import com.yunjihai.rag.solution.model.SolutionModelRequest;
import com.yunjihai.rag.solution.port.OutlineVersionRepository;
import com.yunjihai.rag.solution.port.RequirementVersionRepository;
import com.yunjihai.rag.solution.port.SolutionRepository;
import com.yunjihai.rag.solution.verification.SectionVerificationService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class SectionGenerationService {

    private final SolutionRepository solutions;
    private final RequirementVersionRepository requirements;
    private final OutlineVersionRepository outlines;
    private final SectionVersionRepository sections;
    private final GenerationAttemptStore attempts;
    private final SolutionAuthorizationPort authorization;
    private final RetrievalService retrieval;
    private final SolutionModelPort model;
    private final SectionVerificationService verification;
    private final GenerationPolicy policy;

    public SectionGenerationService(
            SolutionRepository solutions,
            RequirementVersionRepository requirements,
            OutlineVersionRepository outlines,
            SectionVersionRepository sections,
            GenerationAttemptStore attempts,
            SolutionAuthorizationPort authorization,
            RetrievalService retrieval,
            SolutionModelPort model,
            SectionVerificationService verification,
            GenerationPolicy policy) {
        this.solutions = Objects.requireNonNull(solutions, "solutions");
        this.requirements = Objects.requireNonNull(requirements, "requirements");
        this.outlines = Objects.requireNonNull(outlines, "outlines");
        this.sections = Objects.requireNonNull(sections, "sections");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
        this.model = Objects.requireNonNull(model, "model");
        this.verification = Objects.requireNonNull(verification, "verification");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public SectionGenerationResult generate(GenerateSectionCommand command) {
        Objects.requireNonNull(command, "command");
        authorization.require(
                command.actorId(), command.spaceId(), command.solutionId(),
                SolutionAction.ADOPT_SECTION, command.at());
        var context = loadContext(command);
        var key = new GenerationKey(
                command.clientKey(), command.solutionId(),
                context.requirements().id(), context.outline().id(),
                command.sectionId(), command.baselineSectionVersionId());
        var attempt = attempts.insertIfAbsent(GenerationAttempt.start(command.attemptId(), key, command.at()));
        attempt.validateRequest(key);
        if (attempt.status() == GenerationStatus.SUCCEEDED) {
            var completedAttemptId = attempt.id();
            var completed = sections.findById(attempt.resultSectionVersionId().orElseThrow())
                    .or(() -> sections.findByAttemptId(completedAttemptId))
                    .orElseThrow(GenerationStateException::new);
            return new SectionGenerationResult(attempt, Optional.of(completed));
        }
        if (attempt.status() == GenerationStatus.INSUFFICIENT_EVIDENCE
                || attempt.status() == GenerationStatus.FAILED_MANUAL
                || attempt.status() == GenerationStatus.CANCELLED) {
            return new SectionGenerationResult(attempt, Optional.empty());
        }
        if (attempt.status() == GenerationStatus.FAILED_RETRYABLE) {
            var retried = attempt.retry(command.at());
            saveAttempt(retried, attempt);
            attempt = retried;
        }
        if (attempt.status() == GenerationStatus.PENDING) {
            var retrieving = attempt.startRetrieval(command.at());
            saveAttempt(retrieving, attempt);
            attempt = retrieving;
        }
        if (attempt.status() == GenerationStatus.VERIFYING) {
            return new SectionGenerationResult(attempt, Optional.empty());
        }

        com.yunjihai.rag.retrieval.domain.EvidencePack pack;
        try {
            pack = retrieval.retrieve(new RetrievalQuery(
                    command.retrievalRequestId(), command.actorId(), command.spaceId(),
                    queryText(context.requirements(), context.section()),
                    Map.of(), policy.requestedEvidenceCount()));
        } catch (RuntimeException unavailable) {
            return failRetryable(attempt, "RETRIEVAL_UNAVAILABLE", command);
        }
        if (!isCurrent(key)) {
            return cancel(attempt, command);
        }
        if (pack.evidence().size() < policy.minimumEvidenceCount()) {
            if (attempt.status() == GenerationStatus.RETRIEVING) {
                var insufficient = attempt.insufficientEvidence(
                        pack.requestId(), evidenceIds(pack.evidence()), command.at());
                saveAttempt(insufficient, attempt);
                return new SectionGenerationResult(insufficient, Optional.empty());
            }
            return failRetryable(attempt, "RETRIEVAL_INCOMPLETE", command);
        }

        if (attempt.status() == GenerationStatus.RETRIEVING) {
            var generating = attempt.recordEvidence(
                    pack.requestId(), evidenceIds(pack.evidence()), command.at());
            saveAttempt(generating, attempt);
            attempt = generating;
        }
        com.yunjihai.rag.solution.model.SolutionModelResponse<GeneratedSectionDraft> response;
        try {
            response = model.execute(new SolutionModelRequest(
                    command.retrievalRequestId(), command.clientKey(), ModelScenario.SECTION_GENERATION,
                    policy.sectionPrompt(), context.requirements().items(), Optional.of(context.section()),
                    List.of("事实声明必须引用给定证据", "不得使用证据白名单以外的标识"),
                    pack.evidence(), command.at().plus(policy.modelTimeout())),
                    GeneratedSectionDraft.class);
        } catch (RuntimeException unavailable) {
            return failRetryable(attempt, "MODEL_UNAVAILABLE", command);
        }
        if (!isCurrent(key)) {
            return cancel(attempt, command);
        }
        if (response == null) {
            var failed = attempt.failManual("INVALID_MODEL_RESPONSE", command.at());
            saveAttempt(failed, attempt);
            return new SectionGenerationResult(failed, Optional.empty());
        }
        var verifying = attempt.startVerification(response.usage(), command.at());
        saveAttempt(verifying, attempt);
        attempt = verifying;
        SectionVersion version;
        try {
            var report = verification.verify(
                    response.body().claims(), pack.evidence(), command.at().plus(policy.modelTimeout()));
            if (!isCurrent(key)) {
                return cancel(attempt, command);
            }
            var draft = response.body();
            version = new SectionVersion(
                    command.sectionVersionId(), command.solutionId(), command.sectionId(),
                    SectionVersionKind.MODEL_DRAFT, draft.markdown(), draft.claims(), draft.evidenceIds(),
                    context.requirements().id(), context.outline().id(),
                    command.baselineSectionVersionId(), command.baselineSectionVersionId(),
                    Optional.empty(), Optional.of(response.modelId()), command.at(), report);
        } catch (IllegalArgumentException | NullPointerException invalidModelStructure) {
            var failed = attempt.failManual("INVALID_MODEL_STRUCTURE", command.at());
            saveAttempt(failed, attempt);
            return new SectionGenerationResult(failed, Optional.empty());
        }
        var stored = sections.insertIfAbsent(version);
        var succeeded = attempt.succeed(stored.id(), command.at());
        saveAttempt(succeeded, attempt);
        return new SectionGenerationResult(succeeded, Optional.of(stored));
    }

    public Solution adoptSection(
            UUID actorId,
            UUID solutionId,
            UUID sectionVersionId,
            long expectedRevision,
            Instant at) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(solutionId, "solutionId");
        Objects.requireNonNull(sectionVersionId, "sectionVersionId");
        Objects.requireNonNull(at, "at");
        var solution = solutions.findById(solutionId)
                .orElseThrow(() -> new SolutionLifecycleException("SOLUTION_NOT_FOUND"));
        authorization.require(
                actorId, solution.spaceId(), solutionId, SolutionAction.ADOPT_SECTION, at);
        var candidate = sections.findById(sectionVersionId)
                .orElseThrow(() -> new SolutionLifecycleException("SECTION_VERSION_NOT_FOUND"));
        if (solution.revision() != expectedRevision) {
            throw new ConcurrentSolutionUpdateException();
        }
        if (!candidate.solutionId().equals(solutionId)
                || candidate.kind() == SectionVersionKind.ADOPTED) {
            throw new SolutionLifecycleException("SECTION_CANDIDATE_INVALID");
        }
        if (candidate.verification().hasBlockingVerdict()) {
            throw new SolutionLifecycleException("SECTION_VERIFICATION_BLOCKING");
        }
        var outlineId = solution.currentOutlineVersionId()
                .orElseThrow(() -> new SolutionLifecycleException("OUTLINE_NOT_CONFIRMED"));
        if (!solution.currentRequirementVersionId().equals(candidate.requirementVersionId())
                || !outlineId.equals(candidate.outlineVersionId())
                || !solution.requirementConfirmation()
                .map(confirmation -> confirmation.versionId().equals(candidate.requirementVersionId()))
                .orElse(false)
                || !solution.outlineConfirmation()
                .map(confirmation -> confirmation.versionId().equals(candidate.outlineVersionId()))
                .orElse(false)) {
            throw new SolutionLifecycleException("SECTION_CANDIDATE_STALE");
        }
        var currentBaseline = Optional.ofNullable(
                solution.adoptedSectionVersionIds().get(candidate.sectionId()));
        if (!currentBaseline.equals(candidate.baselineSectionVersionId())) {
            throw new SolutionLifecycleException("SECTION_BASELINE_NOT_CURRENT");
        }
        var outline = outlines.findById(outlineId)
                .orElseThrow(() -> new SolutionLifecycleException("OUTLINE_VERSION_NOT_FOUND"));
        if (outline.nodes().stream().noneMatch(node -> node.sectionId().equals(candidate.sectionId()))) {
            throw new SolutionLifecycleException("OUTLINE_SECTION_NOT_FOUND");
        }

        var adopted = new SectionVersion(
                UuidV7.generate(at), candidate.solutionId(), candidate.sectionId(),
                SectionVersionKind.ADOPTED, candidate.content(), candidate.claims(), candidate.evidenceIds(),
                candidate.requirementVersionId(), candidate.outlineVersionId(),
                candidate.baselineSectionVersionId(), Optional.of(candidate.id()),
                Optional.of(actorId), Optional.empty(), at, candidate.verification());
        var updated = solution.adoptSection(candidate.sectionId(), adopted.id(), expectedRevision);
        var stored = sections.insertIfAbsent(adopted);
        if (!stored.id().equals(adopted.id())) {
            throw new GenerationStateException();
        }
        solutions.replace(updated, expectedRevision);
        return updated;
    }

    private SectionGenerationResult failRetryable(
            GenerationAttempt attempt,
            String errorCode,
            GenerateSectionCommand command) {
        var failed = attempt.failRetryable(errorCode, command.at());
        saveAttempt(failed, attempt);
        return new SectionGenerationResult(failed, Optional.empty());
    }

    private SectionGenerationResult cancel(
            GenerationAttempt attempt,
            GenerateSectionCommand command) {
        var cancelled = attempt.cancel(command.at());
        saveAttempt(cancelled, attempt);
        return new SectionGenerationResult(cancelled, Optional.empty());
    }

    private Context loadContext(GenerateSectionCommand command) {
        var solution = loadSolution(command.solutionId(), command.spaceId());
        if (solution.revision() != command.expectedRevision()) {
            throw new ConcurrentSolutionUpdateException();
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
        var requirement = requirements.findById(solution.currentRequirementVersionId())
                .orElseThrow(() -> new SolutionLifecycleException("REQUIREMENT_VERSION_NOT_FOUND"));
        var outline = outlines.findById(outlineId)
                .orElseThrow(() -> new SolutionLifecycleException("OUTLINE_VERSION_NOT_FOUND"));
        var section = outline.nodes().stream()
                .filter(node -> node.sectionId().equals(command.sectionId()))
                .findFirst()
                .orElseThrow(() -> new SolutionLifecycleException("OUTLINE_SECTION_NOT_FOUND"));
        var baseline = Optional.ofNullable(solution.adoptedSectionVersionIds().get(command.sectionId()));
        if (!baseline.equals(command.baselineSectionVersionId())) {
            throw new SolutionLifecycleException("SECTION_BASELINE_NOT_CURRENT");
        }
        return new Context(solution, requirement, outline, section);
    }

    private Solution loadSolution(UUID solutionId, UUID spaceId) {
        var solution = solutions.findById(solutionId)
                .orElseThrow(() -> new SolutionLifecycleException("SOLUTION_NOT_FOUND"));
        if (!solution.spaceId().equals(spaceId)) {
            throw new SolutionLifecycleException("SOLUTION_SPACE_MISMATCH");
        }
        return solution;
    }

    private boolean isCurrent(GenerationKey key) {
        var solution = solutions.findById(key.solutionId()).orElse(null);
        if (solution == null) {
            return false;
        }
        var currentOutline = solution.currentOutlineVersionId();
        var currentBaseline = Optional.ofNullable(
                solution.adoptedSectionVersionIds().get(key.sectionId()));
        return solution.currentRequirementVersionId().equals(key.requirementVersionId())
                && currentOutline.equals(Optional.of(key.outlineVersionId()))
                && currentBaseline.equals(key.baselineSectionVersionId())
                && solution.requirementConfirmation()
                .map(confirmation -> confirmation.versionId().equals(key.requirementVersionId()))
                .orElse(false)
                && solution.outlineConfirmation()
                .map(confirmation -> confirmation.versionId().equals(key.outlineVersionId()))
                .orElse(false);
    }

    private void saveAttempt(GenerationAttempt next, GenerationAttempt previous) {
        attempts.replace(next, previous.revision());
    }

    private static Set<UUID> evidenceIds(List<Evidence> evidence) {
        return evidence.stream().map(Evidence::evidenceId).collect(Collectors.toUnmodifiableSet());
    }

    private static String queryText(RequirementVersion requirements, OutlineNode section) {
        var requirementText = requirements.items().stream()
                .map(item -> item.text())
                .collect(Collectors.joining("；"));
        return requirementText + "\n" + section.objective() + "\n" + section.retrievalHint();
    }

    private record Context(
            Solution solution,
            RequirementVersion requirements,
            OutlineVersion outline,
            OutlineNode section) {
    }
}
