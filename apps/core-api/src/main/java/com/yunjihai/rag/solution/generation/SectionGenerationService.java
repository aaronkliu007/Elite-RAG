package com.yunjihai.rag.solution.generation;

import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.common.UuidV7;
import com.yunjihai.rag.retrieval.RetrievalService;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.RetrievalQuery;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import com.yunjihai.rag.solution.SolutionAction;
import com.yunjihai.rag.solution.SolutionAuthorizationPort;
import com.yunjihai.rag.solution.domain.ConcurrentSolutionUpdateException;
import com.yunjihai.rag.solution.domain.Claim;
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

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class SectionGenerationService implements SectionGenerationPort {

    private final SolutionRepository solutions;
    private final RequirementVersionRepository requirements;
    private final OutlineVersionRepository outlines;
    private final SectionVersionRepository sections;
    private final GenerationAttemptStore attempts;
    private final GenerationEvidenceStore evidenceStore;
    private final SolutionAuthorizationPort authorization;
    private final RetrievalService retrieval;
    private final GenerationEvidenceAuthorizationPort evidenceAuthorization;
    private final SolutionModelPort model;
    private final SectionVerificationService verification;
    private final GenerationPolicy policy;
    private final SectionGenerationCommitPort commit;
    private final AuditSink audit;
    private final Clock clock;

    public SectionGenerationService(
            SolutionRepository solutions,
            RequirementVersionRepository requirements,
            OutlineVersionRepository outlines,
            SectionVersionRepository sections,
            GenerationAttemptStore attempts,
            GenerationEvidenceStore evidenceStore,
            SolutionAuthorizationPort authorization,
            RetrievalService retrieval,
            GenerationEvidenceAuthorizationPort evidenceAuthorization,
            SolutionModelPort model,
            SectionVerificationService verification,
            GenerationPolicy policy,
            SectionGenerationCommitPort commit,
            AuditSink audit) {
        this(solutions, requirements, outlines, sections, attempts, evidenceStore, authorization,
                retrieval, evidenceAuthorization, model, verification, policy, commit, audit, Clock.systemUTC());
    }

    public SectionGenerationService(
            SolutionRepository solutions,
            RequirementVersionRepository requirements,
            OutlineVersionRepository outlines,
            SectionVersionRepository sections,
            GenerationAttemptStore attempts,
            GenerationEvidenceStore evidenceStore,
            SolutionAuthorizationPort authorization,
            RetrievalService retrieval,
            GenerationEvidenceAuthorizationPort evidenceAuthorization,
            SolutionModelPort model,
            SectionVerificationService verification,
            GenerationPolicy policy,
            SectionGenerationCommitPort commit,
            AuditSink audit,
            Clock clock) {
        this.solutions = Objects.requireNonNull(solutions, "solutions");
        this.requirements = Objects.requireNonNull(requirements, "requirements");
        this.outlines = Objects.requireNonNull(outlines, "outlines");
        this.sections = Objects.requireNonNull(sections, "sections");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.evidenceStore = Objects.requireNonNull(evidenceStore, "evidenceStore");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
        this.evidenceAuthorization = Objects.requireNonNull(evidenceAuthorization, "evidenceAuthorization");
        this.model = Objects.requireNonNull(model, "model");
        this.verification = Objects.requireNonNull(verification, "verification");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.commit = Objects.requireNonNull(commit, "commit");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SectionGenerationResult generate(GenerateSectionCommand command) {
        Objects.requireNonNull(command, "command");
        try {
            authorization.require(
                    command.actorId(), command.spaceId(), command.solutionId(),
                    SolutionAction.ADOPT_SECTION, command.at());
        } catch (RuntimeException denied) {
            recordFailure(command.actorId(), command.solutionId(), AuditAction.SECTION_GENERATE,
                    command.retrievalRequestId(), command.spaceId(), "AUTHORIZATION_FAILED", denied);
            throw denied;
        }
        try {
            var result = generateAuthorized(command);
            if (!isActive(result.attempt().status())) {
                recordGeneration(command, result.attempt());
            }
            return result;
        } catch (RuntimeException failure) {
            recordFailure(command.actorId(), command.solutionId(), AuditAction.SECTION_GENERATE,
                    command.retrievalRequestId(), command.spaceId(), "SECTION_GENERATION_FAILED", failure);
            throw failure;
        }
    }

    private SectionGenerationResult generateAuthorized(GenerateSectionCommand command) {
        var context = loadContext(command);
        var key = new GenerationKey(
                command.clientKey(), command.solutionId(),
                context.requirements().id(), context.outline().id(),
                command.sectionId(), command.baselineSectionVersionId());
        var registration = attempts.register(GenerationAttempt.start(
                command.attemptId(), key, attempts.authoritativeNow(command.at())));
        var attempt = registration.attempt();
        attempt.validateRequest(key);
        if (attempt.status() == GenerationStatus.SUCCEEDED) {
            authorizeStoredEvidence(command.actorId(), command.spaceId(), attempt.id(),
                    attempt.evidenceIds());
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
        if (!registration.created() && isActive(attempt.status())) {
            if (attempt.status() == GenerationStatus.VERIFYING) {
                if (sections.findByAttemptId(attempt.id()).isPresent()
                        || leaseExpired(attempt, attempts.authoritativeNow(command.at()))) {
                    return recoverVerification(attempt, command);
                }
                return new SectionGenerationResult(attempt, Optional.empty());
            }
            if (!leaseExpired(attempt, attempts.authoritativeNow(command.at()))) {
                return new SectionGenerationResult(attempt, Optional.empty());
            }
            var expired = attempt.failRetryable("WORKER_LEASE_EXPIRED", progressAt(attempt, command.at()));
            saveAttempt(expired, attempt);
            attempt = expired;
        }
        if (attempt.status() == GenerationStatus.FAILED_RETRYABLE) {
            if (attempt.executionCount() >= policy.maxExecutions()) {
                var exhausted = attempt.exhaustRetries(progressAt(attempt, command.at()));
                saveAttempt(exhausted, attempt);
                return new SectionGenerationResult(exhausted, Optional.empty());
            }
            var retried = attempt.retry(progressAt(attempt, command.at()));
            saveAttempt(retried, attempt);
            attempt = retried;
        }
        if (attempt.status() == GenerationStatus.PENDING) {
            var retrieving = attempt.startRetrieval(progressAt(attempt, command.at()));
            saveAttempt(retrieving, attempt);
            attempt = retrieving;
        }

        com.yunjihai.rag.retrieval.domain.EvidencePack pack;
        try {
            pack = retrieval.retrieve(new RetrievalQuery(
                    command.retrievalRequestId(), command.actorId(), Set.of(command.spaceId()),
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
                        pack.requestId(), evidenceIds(pack.evidence()), progressAt(attempt, command.at()));
                saveAttempt(insufficient, attempt);
                return new SectionGenerationResult(insufficient, Optional.empty());
            }
            return failRetryable(attempt, "RETRIEVAL_INCOMPLETE", command);
        }

        List<Evidence> selectedEvidence = pack.evidence();
        List<GenerationEvidenceSource> snapshot;
        if (attempt.status() == GenerationStatus.GENERATING) {
            snapshot = evidenceStore.findByAttemptId(attempt.id());
            if (snapshot.isEmpty() || !sourceIds(snapshot).equals(attempt.evidenceIds())) {
                var failed = attempt.failManual("EVIDENCE_SNAPSHOT_MISSING", progressAt(attempt, command.at()));
                saveAttempt(failed, attempt);
                return new SectionGenerationResult(failed, Optional.empty());
            }
            try {
                selectedEvidence = alignEvidence(snapshot, pack.evidence());
            } catch (IllegalArgumentException changed) {
                var failed = attempt.failManual("EVIDENCE_SNAPSHOT_CHANGED", progressAt(attempt, command.at()));
                saveAttempt(failed, attempt);
                return new SectionGenerationResult(failed, Optional.empty());
            }
        } else {
            snapshot = sourceSnapshot(selectedEvidence);
        }
        var selectedEvidenceIds = evidenceIds(selectedEvidence);
        if (attempt.status() == GenerationStatus.RETRIEVING) {
            var generating = attempt.recordEvidence(
                    pack.requestId(), selectedEvidenceIds, progressAt(attempt, command.at()));
            saveAttempt(generating, attempt);
            attempt = generating;
            evidenceStore.saveSnapshot(attempt.id(), snapshot);
        }
        try {
            authorization.require(command.actorId(), command.spaceId(), command.solutionId(),
                    SolutionAction.ADOPT_SECTION, clock.instant());
            evidenceAuthorization.requireReadable(command.actorId(), command.spaceId(),
                    snapshot, clock.instant());
        } catch (RuntimeException denied) {
            cancel(attempt, command);
            throw denied;
        }
        var refreshed = attempt.heartbeat(progressAt(attempt, command.at()));
        saveAttempt(refreshed, attempt);
        attempt = refreshed;
        com.yunjihai.rag.solution.model.SolutionModelResponse<GeneratedSectionDraft> response;
        try {
            response = model.execute(new SolutionModelRequest(
                    command.retrievalRequestId(), command.clientKey(), ModelScenario.SECTION_GENERATION,
                    policy.sectionPrompt(), context.requirements().items(), Optional.of(context.section()),
                    List.of("事实声明必须引用给定证据", "不得使用证据白名单以外的标识"),
                    selectedEvidence, command.at().plus(policy.modelTimeout())),
                    GeneratedSectionDraft.class);
        } catch (ModelGatewayException denied) {
            if (denied.kind() == ModelGatewayException.Kind.POLICY_VIOLATION) {
                var failed = attempt.failManual("MODEL_POLICY_VIOLATION", progressAt(attempt, command.at()));
                saveAttempt(failed, attempt);
                return new SectionGenerationResult(failed, Optional.empty());
            }
            return failRetryable(attempt, "MODEL_UNAVAILABLE", command);
        } catch (RuntimeException unavailable) {
            return failRetryable(attempt, "MODEL_UNAVAILABLE", command);
        }
        if (response == null) {
            var failed = attempt.failManual("INVALID_MODEL_RESPONSE", progressAt(attempt, command.at()));
            saveAttempt(failed, attempt);
            return new SectionGenerationResult(failed, Optional.empty());
        }
        var verifying = attempt.startVerification(response.usage(), progressAt(attempt, command.at()));
        saveAttempt(verifying, attempt);
        if (!isCurrent(key)) {
            return cancel(verifying, command);
        }
        SectionVersion version;
        try {
            var governedClaims = applyOutlineRisks(response.body().claims(), context.section());
            var report = verification.verify(
                    governedClaims, selectedEvidence, command.at().plus(policy.modelTimeout()));
            if (!isCurrent(key)) {
                return cancel(verifying, command);
            }
            var draft = response.body();
            version = new SectionVersion(
                    command.sectionVersionId(), command.solutionId(), command.sectionId(),
                    SectionVersionKind.MODEL_DRAFT, draft.markdown(), governedClaims, draft.evidenceIds(),
                    context.requirements().id(), context.outline().id(),
                    command.baselineSectionVersionId(), command.baselineSectionVersionId(),
                    Optional.empty(), Optional.of(response.modelId()), command.at(), report);
        } catch (IllegalArgumentException | NullPointerException invalidModelStructure) {
            var failed = verifying.failManual("INVALID_MODEL_STRUCTURE", progressAt(verifying, command.at()));
            saveAttempt(failed, verifying);
            return new SectionGenerationResult(failed, Optional.empty());
        }
        try {
            authorization.require(command.actorId(), command.spaceId(), command.solutionId(),
                    SolutionAction.ADOPT_SECTION, clock.instant());
            evidenceAuthorization.requireReadable(command.actorId(), command.spaceId(),
                    snapshot, clock.instant());
        } catch (RuntimeException denied) {
            cancel(verifying, command);
            throw denied;
        }
        var succeeded = verifying.succeed(version.id(), progressAt(verifying, command.at()));
        if (!commit.commitIfCurrent(key, attempt.id(), version, succeeded, verifying.revision())) {
            return cancel(verifying, command);
        }
        return new SectionGenerationResult(succeeded, Optional.of(version));
    }

    @Override
    public SectionGenerationResult readAttempt(UUID actorId, UUID spaceId, UUID solutionId,
            UUID sectionId, String clientKey, Instant at) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(solutionId);
        Objects.requireNonNull(sectionId);
        Objects.requireNonNull(clientKey);
        Objects.requireNonNull(at);
        authorization.require(actorId, spaceId, solutionId, SolutionAction.ADOPT_SECTION, clock.instant());
        var attempt = attempts.findByClientKey(clientKey)
                .orElseThrow(AccessDeniedAsNotFoundException::new);
        var key = attempt.key();
        if (!key.solutionId().equals(solutionId) || !key.sectionId().equals(sectionId)) {
            throw new AccessDeniedAsNotFoundException();
        }
        var solution = loadSolution(solutionId, spaceId);
        var context = loadContext(solutionId, spaceId, sectionId,
                key.baselineSectionVersionId(), solution.revision());
        if (!context.requirements().id().equals(key.requirementVersionId())
                || !context.outline().id().equals(key.outlineVersionId())) {
            throw new GenerationRequestConflictException();
        }
        if (attempt.status() != GenerationStatus.SUCCEEDED) {
            return new SectionGenerationResult(attempt, Optional.empty());
        }
        authorizeStoredEvidence(actorId, spaceId, attempt.id(), attempt.evidenceIds());
        var version = sections.findById(attempt.resultSectionVersionId().orElseThrow())
                .orElseThrow(GenerationStateException::new);
        if (!version.solutionId().equals(solutionId) || !version.sectionId().equals(sectionId)
                || !version.requirementVersionId().equals(key.requirementVersionId())
                || !version.outlineVersionId().equals(key.outlineVersionId())) {
            throw new GenerationStateException();
        }
        return new SectionGenerationResult(attempt, Optional.of(version));
    }

    private void authorizeStoredEvidence(UUID actorId, UUID spaceId, UUID attemptId,
            Set<UUID> expectedEvidenceIds) {
        var snapshot = evidenceStore.findByAttemptId(attemptId);
        if (snapshot.isEmpty() || !sourceIds(snapshot).equals(expectedEvidenceIds)) {
            throw new AccessDeniedAsNotFoundException();
        }
        evidenceAuthorization.requireReadable(actorId, spaceId, snapshot, clock.instant());
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
        try {
            var updated = adoptSectionAuthorized(
                    actorId, solutionId, sectionVersionId, expectedRevision, at);
            record(actorId, solutionId, AuditAction.SECTION_ADOPT, sectionVersionId,
                    updated.spaceId(), AuditOutcome.SUCCEEDED, Map.of(
                            "sectionVersionId", sectionVersionId.toString(),
                            "status", "SUCCEEDED"));
            return updated;
        } catch (RuntimeException failure) {
            recordFailure(actorId, solutionId, AuditAction.SECTION_ADOPT, sectionVersionId,
                    null, "SECTION_ADOPTION_FAILED", failure);
            throw failure;
        }
    }

    private Solution adoptSectionAuthorized(
            UUID actorId,
            UUID solutionId,
            UUID sectionVersionId,
            long expectedRevision,
            Instant at) {
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
        var candidateSources = evidenceStore.findBySectionVersionId(candidate.id());
        var citedSources = candidateSources.stream()
                .filter(source -> candidate.evidenceIds().contains(source.evidenceId()))
                .toList();
        if (citedSources.isEmpty() || !sourceIds(citedSources).equals(candidate.evidenceIds())) {
            throw new AccessDeniedAsNotFoundException();
        }
        evidenceAuthorization.requireReadable(actorId, solution.spaceId(), citedSources, clock.instant());
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
        var failed = attempt.failRetryable(errorCode, progressAt(attempt, command.at()));
        saveAttempt(failed, attempt);
        return new SectionGenerationResult(failed, Optional.empty());
    }

    private SectionGenerationResult cancel(
            GenerationAttempt withLatestResults,
            GenerationAttempt persisted,
            GenerateSectionCommand command) {
        var cancelled = withLatestResults.cancel(progressAt(withLatestResults, command.at()));
        saveAttempt(cancelled, persisted);
        return new SectionGenerationResult(cancelled, Optional.empty());
    }

    private SectionGenerationResult cancel(
            GenerationAttempt attempt,
            GenerateSectionCommand command) {
        return cancel(attempt, attempt, command);
    }

    private SectionGenerationResult recoverVerification(
            GenerationAttempt attempt,
            GenerateSectionCommand command) {
        var checkpoint = sections.findByAttemptId(attempt.id());
        if (checkpoint.isPresent()) {
            authorizeStoredEvidence(command.actorId(), command.spaceId(), attempt.id(),
                    attempt.evidenceIds());
            var succeeded = attempt.succeed(checkpoint.orElseThrow().id(), progressAt(attempt, command.at()));
            saveAttempt(succeeded, attempt);
            return new SectionGenerationResult(succeeded, checkpoint);
        }
        var failed = attempt.failManual("VERIFICATION_CHECKPOINT_MISSING", progressAt(attempt, command.at()));
        saveAttempt(failed, attempt);
        return new SectionGenerationResult(failed, Optional.empty());
    }

    private Context loadContext(GenerateSectionCommand command) {
        return loadContext(command.solutionId(), command.spaceId(), command.sectionId(),
                command.baselineSectionVersionId(), command.expectedRevision());
    }

    private Context loadContext(UUID solutionId, UUID spaceId, UUID sectionId,
            Optional<UUID> baselineSectionVersionId, long expectedRevision) {
        var solution = loadSolution(solutionId, spaceId);
        if (solution.revision() != expectedRevision) {
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
                .filter(node -> node.sectionId().equals(sectionId))
                .findFirst()
                .orElseThrow(() -> new SolutionLifecycleException("OUTLINE_SECTION_NOT_FOUND"));
        var baseline = Optional.ofNullable(solution.adoptedSectionVersionIds().get(sectionId));
        if (!baseline.equals(baselineSectionVersionId)) {
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

    private Instant progressAt(GenerationAttempt attempt, Instant requestAt) {
        var now = clock.instant();
        if (now.isBefore(requestAt)) now = requestAt;
        now = attempts.authoritativeNow(now);
        return now.isBefore(attempt.updatedAt()) ? attempt.updatedAt() : now;
    }

    private static Set<UUID> evidenceIds(List<Evidence> evidence) {
        return evidence.stream().map(Evidence::evidenceId).collect(Collectors.toUnmodifiableSet());
    }

    private static Set<UUID> sourceIds(List<GenerationEvidenceSource> sources) {
        return sources.stream().map(GenerationEvidenceSource::evidenceId)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static List<GenerationEvidenceSource> sourceSnapshot(List<Evidence> evidence) {
        var sources = new java.util.ArrayList<GenerationEvidenceSource>(evidence.size());
        for (int index = 0; index < evidence.size(); index++) {
            sources.add(GenerationEvidenceSource.from(index + 1, evidence.get(index)));
        }
        return List.copyOf(sources);
    }

    private static List<Evidence> alignEvidence(
            List<GenerationEvidenceSource> snapshot, List<Evidence> current) {
        var aligned = new java.util.ArrayList<Evidence>(snapshot.size());
        for (var source : snapshot) {
            var match = current.stream().filter(source::matches).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("evidence source changed"));
            aligned.add(source.withOriginalId(match));
        }
        return List.copyOf(aligned);
    }

    private static List<Claim> applyOutlineRisks(List<Claim> claims, OutlineNode section) {
        return claims.stream().map(claim -> {
            if (!claim.factual() || section.riskTypes().isEmpty()) {
                return claim;
            }
            var risks = new java.util.HashSet<>(claim.riskTypes());
            risks.addAll(section.riskTypes());
            return new Claim(
                    claim.claimId(), claim.text(), true, claim.evidenceIds(), Set.copyOf(risks));
        }).toList();
    }

    private void recordGeneration(GenerateSectionCommand command, GenerationAttempt attempt) {
        var outcome = switch (attempt.status()) {
            case SUCCEEDED, INSUFFICIENT_EVIDENCE -> AuditOutcome.SUCCEEDED;
            default -> AuditOutcome.FAILED;
        };
        var details = new java.util.HashMap<String, String>();
        details.put("sectionVersionId", command.sectionVersionId().toString());
        details.put("status", attempt.status().name());
        attempt.failureCode().ifPresent(code -> details.put("errorCode", code));
        record(command.actorId(), command.solutionId(), AuditAction.SECTION_GENERATE,
                command.retrievalRequestId(), command.spaceId(), outcome, details);
    }

    private void recordFailure(
            UUID actorId,
            UUID solutionId,
            AuditAction action,
            UUID requestId,
            UUID spaceId,
            String errorCode,
            RuntimeException original) {
        try {
            var details = new java.util.HashMap<String, String>();
            details.put("status", "FAILED");
            details.put("errorCode", errorCode);
            record(actorId, solutionId, action, requestId, spaceId,
                    original instanceof SecurityException ? AuditOutcome.DENIED : AuditOutcome.FAILED,
                    details);
        } catch (RuntimeException auditFailure) {
            original.addSuppressed(auditFailure);
        }
    }

    private void record(
            UUID actorId,
            UUID solutionId,
            AuditAction action,
            UUID requestId,
            UUID spaceId,
            AuditOutcome outcome,
            Map<String, String> details) {
        var metadata = new java.util.HashMap<String, String>();
        metadata.put("solutionId", solutionId.toString());
        metadata.put("requestId", requestId.toString());
        if (spaceId != null) {
            metadata.put("spaceId", spaceId.toString());
        }
        metadata.putAll(details);
        audit.record(new AuditCommand(
                actorId, action, "SOLUTION", solutionId, outcome, Map.copyOf(metadata)));
    }

    private static boolean isActive(GenerationStatus status) {
        return status == GenerationStatus.PENDING
                || status == GenerationStatus.RETRIEVING
                || status == GenerationStatus.GENERATING
                || status == GenerationStatus.VERIFYING;
    }

    private boolean leaseExpired(GenerationAttempt attempt, Instant at) {
        return !at.isBefore(attempt.updatedAt().plus(policy.recoveryAfter()));
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
