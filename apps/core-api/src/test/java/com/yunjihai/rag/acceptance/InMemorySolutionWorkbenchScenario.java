package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.RetrievalService;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.EvidencePack;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.domain.RetrievalStats;
import com.yunjihai.rag.solution.SolutionAuthorizationPort;
import com.yunjihai.rag.solution.SolutionWorkbenchService;
import com.yunjihai.rag.solution.domain.Claim;
import com.yunjihai.rag.solution.domain.ConcurrentSolutionUpdateException;
import com.yunjihai.rag.solution.domain.OutlineNode;
import com.yunjihai.rag.solution.domain.OutlineVersion;
import com.yunjihai.rag.solution.domain.RequirementCategory;
import com.yunjihai.rag.solution.domain.RequirementItem;
import com.yunjihai.rag.solution.domain.RequirementSource;
import com.yunjihai.rag.solution.domain.RequirementVersion;
import com.yunjihai.rag.solution.domain.RiskType;
import com.yunjihai.rag.solution.domain.SectionVersion;
import com.yunjihai.rag.solution.domain.Solution;
import com.yunjihai.rag.solution.domain.SolutionLifecycleException;
import com.yunjihai.rag.solution.domain.SolutionPhase;
import com.yunjihai.rag.solution.finalization.FinalizedSolutionVersion;
import com.yunjihai.rag.solution.finalization.SolutionFinalizationService;
import com.yunjihai.rag.solution.finalization.SolutionFinalizationStore;
import com.yunjihai.rag.solution.generation.GenerateSectionCommand;
import com.yunjihai.rag.solution.generation.GenerationAttempt;
import com.yunjihai.rag.solution.generation.GenerationAttemptStore;
import com.yunjihai.rag.solution.generation.GenerationPolicy;
import com.yunjihai.rag.solution.generation.GenerationRequestConflictException;
import com.yunjihai.rag.solution.generation.GenerationStatus;
import com.yunjihai.rag.solution.generation.ModelUsage;
import com.yunjihai.rag.solution.generation.SectionGenerationService;
import com.yunjihai.rag.solution.generation.SectionVersionRepository;
import com.yunjihai.rag.solution.model.GeneratedSectionDraft;
import com.yunjihai.rag.solution.model.SolutionModelPort;
import com.yunjihai.rag.solution.model.SolutionModelRequest;
import com.yunjihai.rag.solution.model.SolutionModelResponse;
import com.yunjihai.rag.solution.port.OutlineVersionRepository;
import com.yunjihai.rag.solution.port.RequirementVersionRepository;
import com.yunjihai.rag.solution.port.SolutionRepository;
import com.yunjihai.rag.solution.verification.CitationVerdict;
import com.yunjihai.rag.solution.verification.ClaimVerification;
import com.yunjihai.rag.solution.verification.RiskConfirmation;
import com.yunjihai.rag.solution.verification.RiskConfirmationRepository;
import com.yunjihai.rag.solution.verification.SectionVerificationService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class InMemorySolutionWorkbenchScenario {

    private static final UUID ACTOR_ID = id(1);
    private static final UUID SPACE_ID = id(2);
    private static final UUID SOLUTION_ID = id(3);
    private static final UUID REQUIREMENT_ID = id(10);
    private static final UUID NEXT_REQUIREMENT_ID = id(11);
    private static final UUID OUTLINE_ID = id(20);
    private static final UUID REQUIRED_SECTION_ID = id(30);
    private static final UUID OPTIONAL_SECTION_ID = id(31);
    private static final UUID BAD_EVIDENCE_ID = id(40);
    private static final UUID GOOD_EVIDENCE_ID = id(41);
    private static final UUID CLAIM_ID = id(50);
    private static final Instant NOW = Instant.parse("2026-09-28T08:00:00Z");

    private final InMemorySolutionRepository solutions = new InMemorySolutionRepository();
    private final InMemoryRequirementRepository requirements = new InMemoryRequirementRepository();
    private final InMemoryOutlineRepository outlines = new InMemoryOutlineRepository();
    private final InMemoryAttemptStore attempts = new InMemoryAttemptStore();
    private final InMemorySectionRepository sections = new InMemorySectionRepository();
    private final InMemoryRiskRepository confirmations = new InMemoryRiskRepository();
    private final List<AuditCommand> audits = new ArrayList<>();
    private final List<FinalizedSolutionVersion> finalized = new ArrayList<>();
    private int retrievalCalls;
    private int modelCalls;
    private int evidenceReauthorizationCalls;

    ScenarioResult run() {
        AuditSink audit = command -> {
            audits.add(command);
            return null;
        };
        SolutionAuthorizationPort authorization = SolutionAuthorizationPort.allowAll();
        SolutionModelPort model = new SolutionModelPort() {
            @Override
            public <T> SolutionModelResponse<T> execute(
                    SolutionModelRequest request,
                    Class<T> responseType) {
                modelCalls++;
                var cited = request.evidence().getFirst().evidenceId();
                var claim = new Claim(
                        CLAIM_ID,
                        "系统支持100名用户",
                        true,
                        Set.of(cited),
                        Set.of(RiskType.PERFORMANCE_OR_SLA));
                var draft = new GeneratedSectionDraft(
                        "## 总体方案\n系统支持100名用户。",
                        List.of(claim),
                        Set.of(cited));
                return new SolutionModelResponse<>(
                        responseType,
                        responseType.cast(draft),
                        "logical-model-v1",
                        new ModelUsage(100, 50, 1234, 800));
            }
        };
        var workbench = new SolutionWorkbenchService(
                solutions, requirements, outlines, authorization, model, audit);

        var requirement = requirement(REQUIREMENT_ID, 1, "建设企业知识库");
        var solution = workbench.createSolution(new SolutionWorkbenchService.CreateSolutionCommand(
                ACTOR_ID, SPACE_ID, SOLUTION_ID, requirement, 0, id(100), NOW));
        solution = workbench.confirmRequirements(new SolutionWorkbenchService.ConfirmRequirementsCommand(
                ACTOR_ID, SPACE_ID, SOLUTION_ID, REQUIREMENT_ID,
                solution.revision(), id(101), NOW.plusSeconds(1)));
        var outline = outline();
        solution = workbench.reviseOutline(new SolutionWorkbenchService.ReviseOutlineCommand(
                ACTOR_ID, SPACE_ID, SOLUTION_ID, outline,
                solution.revision(), id(102), NOW.plusSeconds(2)));
        solution = workbench.confirmOutline(new SolutionWorkbenchService.ConfirmOutlineCommand(
                ACTOR_ID, SPACE_ID, SOLUTION_ID, OUTLINE_ID,
                solution.revision(), id(103), NOW.plusSeconds(3)));

        RetrievalService retrieval = query -> {
            retrievalCalls++;
            if (query.requestId().equals(id(70))) {
                return pack(query.requestId(), List.of());
            }
            if (query.requestId().equals(id(71))) {
                return pack(query.requestId(), List.of(evidence(
                        BAD_EVIDENCE_ID, "客户材料记载系统支持60名用户")));
            }
            return pack(query.requestId(), List.of(evidence(
                    GOOD_EVIDENCE_ID, "客户材料记载系统支持100名用户")));
        };
        var verification = new SectionVerificationService((inputs, deadline) -> inputs.stream()
                .map(input -> new ClaimVerification(
                        input.claimId(), CitationVerdict.SUPPORTED, "SEMANTIC_SUPPORTED"))
                .toList());
        var generation = new SectionGenerationService(
                solutions, requirements, outlines, sections, attempts,
                authorization, retrieval, model, verification, GenerationPolicy.defaults(),
                (key, attemptId, version, succeeded, expectedAttemptRevision) -> {
                    var current = solutions.findById(key.solutionId()).orElseThrow();
                    if (!current.currentRequirementVersionId().equals(key.requirementVersionId())
                            || !current.currentOutlineVersionId().equals(Optional.of(key.outlineVersionId()))
                            || !Optional.ofNullable(current.adoptedSectionVersionIds().get(key.sectionId()))
                            .equals(key.baselineSectionVersionId())) {
                        return false;
                    }
                    sections.insertForAttempt(attemptId, version);
                    attempts.replace(succeeded, expectedAttemptRevision);
                    return true;
                }, audit);

        var insufficient = generation.generate(command(
                OPTIONAL_SECTION_ID, id(60), id(61), id(70), "optional-empty", NOW.plusSeconds(4)));
        var callsAfterInsufficient = modelCalls;
        var unsupported = generation.generate(command(
                REQUIRED_SECTION_ID, id(62), id(63), id(71), "required-bad", NOW.plusSeconds(5)));
        var unsupportedBlocked = false;
        try {
            generation.adoptSection(
                    ACTOR_ID, SOLUTION_ID, unsupported.sectionVersion().orElseThrow().id(),
                    solution.revision(), NOW.plusSeconds(6));
        } catch (SolutionLifecycleException blocked) {
            unsupportedBlocked = "SECTION_VERIFICATION_BLOCKING".equals(blocked.errorCode());
        }
        var supported = generation.generate(command(
                REQUIRED_SECTION_ID, id(64), id(65), id(72), "required-good", NOW.plusSeconds(7)));
        solution = generation.adoptSection(
                ACTOR_ID, SOLUTION_ID, supported.sectionVersion().orElseThrow().id(),
                solution.revision(), NOW.plusSeconds(8));
        var adoptedVersionId = solution.adoptedSectionVersionIds().get(REQUIRED_SECTION_ID);

        SolutionFinalizationStore finalizationStore = (updated, expectedRevision, version, successAudit) -> {
            solutions.replace(updated, expectedRevision);
            finalized.add(version);
            audits.add(successAudit);
        };
        var finalization = new SolutionFinalizationService(
                solutions, outlines, sections, confirmations, authorization,
                (userId, spaceId, evidenceIds, at) -> {
                    evidenceReauthorizationCalls++;
                    return evidenceIds;
                },
                finalizationStore,
                audit);
        var riskBlocked = false;
        try {
            finalization.finalizeSolution(
                    ACTOR_ID, SOLUTION_ID, solution.revision(), NOW.plusSeconds(9));
        } catch (SolutionLifecycleException blocked) {
            riskBlocked = "RISK_CONFIRMATION_REQUIRED".equals(blocked.errorCode());
        }
        var confirmation = finalization.confirmRisk(
                ACTOR_ID, SOLUTION_ID, adoptedVersionId, CLAIM_ID,
                "负责人确认性能承诺", NOW.plusSeconds(10));
        var finalVersion = finalization.finalizeSolution(
                ACTOR_ID, SOLUTION_ID, solution.revision(), NOW.plusSeconds(11));
        var finalizedSolution = solutions.findById(SOLUTION_ID).orElseThrow();

        var nextRequirement = requirement(NEXT_REQUIREMENT_ID, 2, "建设升级版企业知识库");
        requirements.insert(nextRequirement);
        var nextRevision = finalizedSolution.startNewRevision(
                nextRequirement, finalizedSolution.revision());
        solutions.replace(nextRevision, finalizedSolution.revision());
        var oldAdopted = sections.findById(adoptedVersionId).orElseThrow();

        return new ScenarioResult(
                insufficient.attempt().status(),
                callsAfterInsufficient,
                unsupportedBlocked,
                riskBlocked,
                finalized.size() == 1,
                evidenceReauthorizationCalls,
                finalVersion.sectionVersionIds().size(),
                finalVersion.riskConfirmationIds().size(),
                finalVersion.contentHash(),
                nextRevision.phase(),
                nextRevision.solutionRevision(),
                nextRevision.currentOutlineVersionId().isEmpty(),
                nextRevision.adoptedSectionVersionIds().isEmpty(),
                nextRevision.finalizedVersionId().isEmpty(),
                !nextRevision.currentRequirementVersionId().equals(confirmation.requirementVersionId())
                        || !confirmation.matches(oldAdopted),
                finalized.getFirst().equals(finalVersion),
                retrievalCalls,
                modelCalls,
                audits.size());
    }

    private static GenerateSectionCommand command(
            UUID sectionId,
            UUID attemptId,
            UUID sectionVersionId,
            UUID retrievalRequestId,
            String clientKey,
            Instant at) {
        return new GenerateSectionCommand(
                ACTOR_ID, SPACE_ID, SOLUTION_ID, sectionId,
                attemptId, sectionVersionId, retrievalRequestId,
                clientKey, Optional.empty(), 3, at);
    }

    private static RequirementVersion requirement(
            UUID id,
            long number,
            String text) {
        return new RequirementVersion(
                id,
                number,
                List.of(new RequirementItem(
                        InMemorySolutionWorkbenchScenario.id(12 + (int) number),
                        RequirementCategory.GOAL,
                        text,
                        List.of(new RequirementSource(
                                new DocumentVersionRef(
                                        InMemorySolutionWorkbenchScenario.id(14),
                                        InMemorySolutionWorkbenchScenario.id(15)),
                                new MarkdownLocator(List.of("客户需求"), "node-1"))))),
                ACTOR_ID,
                NOW,
                number == 1 ? "首次需求" : "新修订");
    }

    private static OutlineVersion outline() {
        return new OutlineVersion(
                OUTLINE_ID,
                1,
                List.of(
                        new OutlineNode(
                                REQUIRED_SECTION_ID, "总体方案", 1, 1, true,
                                "描述总体架构", "检索总体架构", Set.of(RiskType.PERFORMANCE_OR_SLA)),
                        new OutlineNode(
                                OPTIONAL_SECTION_ID, "补充材料", 1, 2, false,
                                "描述补充资料", "检索补充资料", Set.of())),
                ACTOR_ID,
                NOW);
    }

    private static Evidence evidence(UUID evidenceId, String excerpt) {
        return new Evidence(
                evidenceId, id(80), id(81), "node-1", "客户材料", excerpt, 0.9,
                new MarkdownLocator(List.of("客户材料"), "node-1"));
    }

    private static EvidencePack pack(UUID requestId, List<Evidence> evidence) {
        return new EvidencePack(
                requestId,
                evidence,
                false,
                Set.of(),
                new RetrievalStats(1, 1, 1, evidence.size(), evidence.size()),
                NOW);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
    }

    record ScenarioResult(
            GenerationStatus insufficientAttemptStatus,
            int modelCallsAfterInsufficientEvidence,
            boolean unsupportedAdoptionBlocked,
            boolean riskBlockedFinalization,
            boolean finalizedSnapshotCreated,
            int evidenceReauthorizationCalls,
            int finalizedSectionCount,
            int finalizedRiskConfirmationCount,
            String finalizedContentHash,
            SolutionPhase newRevisionPhase,
            long newSolutionRevision,
            boolean oldOutlineInvalidated,
            boolean oldSectionsInvalidated,
            boolean oldFinalizationInvalidated,
            boolean oldRiskConfirmationNoLongerMatchesCurrent,
            boolean persistedFinalizedSnapshotUnchanged,
            int retrievalCalls,
            int modelCalls,
            int auditCount) {
    }

    private static final class InMemorySolutionRepository implements SolutionRepository {
        private final Map<UUID, Solution> values = new HashMap<>();

        @Override
        public Optional<Solution> findById(UUID solutionId) {
            return Optional.ofNullable(values.get(solutionId));
        }

        @Override
        public void insert(Solution solution) {
            if (values.putIfAbsent(solution.id(), solution) != null) {
                throw new IllegalStateException("duplicate solution");
            }
        }

        @Override
        public void replace(Solution solution, long expectedRevision) {
            values.compute(solution.id(), (id, current) -> {
                if (current == null || current.revision() != expectedRevision) {
                    throw new ConcurrentSolutionUpdateException();
                }
                return solution;
            });
        }
    }

    private static final class InMemoryRequirementRepository implements RequirementVersionRepository {
        private final Map<UUID, RequirementVersion> values = new HashMap<>();

        @Override
        public Optional<RequirementVersion> findById(UUID id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public void insert(RequirementVersion version) {
            values.putIfAbsent(version.id(), version);
        }
    }

    private static final class InMemoryOutlineRepository implements OutlineVersionRepository {
        private final Map<UUID, OutlineVersion> values = new HashMap<>();

        @Override
        public Optional<OutlineVersion> findById(UUID id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public void insert(OutlineVersion version) {
            values.putIfAbsent(version.id(), version);
        }
    }

    private static final class InMemoryAttemptStore implements GenerationAttemptStore {
        private final Map<String, GenerationAttempt> values = new HashMap<>();

        @Override
        public Optional<GenerationAttempt> findByClientKey(String clientKey) {
            return Optional.ofNullable(values.get(clientKey));
        }

        @Override
        public com.yunjihai.rag.solution.generation.GenerationAttemptRegistration register(
                GenerationAttempt attempt) {
            var existing = values.putIfAbsent(attempt.key().clientKey(), attempt);
            return new com.yunjihai.rag.solution.generation.GenerationAttemptRegistration(
                    existing == null ? attempt : existing, existing == null);
        }

        @Override
        public void replace(GenerationAttempt next, long expectedRevision) {
            values.compute(next.key().clientKey(), (key, current) -> {
                if (current == null || current.revision() != expectedRevision) {
                    throw new GenerationRequestConflictException();
                }
                return next;
            });
        }
    }

    private static final class InMemorySectionRepository implements SectionVersionRepository {
        private final Map<UUID, SectionVersion> values = new HashMap<>();
        private final Map<UUID, SectionVersion> byAttempt = new HashMap<>();

        @Override
        public Optional<SectionVersion> findById(UUID id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public SectionVersion insertIfAbsent(SectionVersion version) {
            return values.computeIfAbsent(version.id(), ignored -> version);
        }

        @Override
        public Optional<SectionVersion> findByAttemptId(UUID attemptId) {
            return Optional.ofNullable(byAttempt.get(attemptId));
        }

        private void insertForAttempt(UUID attemptId, SectionVersion version) {
            values.putIfAbsent(version.id(), version);
            byAttempt.putIfAbsent(attemptId, version);
        }
    }

    private static final class InMemoryRiskRepository implements RiskConfirmationRepository {
        private final List<RiskConfirmation> values = new ArrayList<>();

        @Override
        public List<RiskConfirmation> findBySolutionId(UUID solutionId) {
            return values.stream().filter(value -> value.solutionId().equals(solutionId)).toList();
        }

        @Override
        public RiskConfirmation insertIfAbsent(RiskConfirmation confirmation) {
            return values.stream()
                    .filter(existing -> existing.bindingKey().equals(confirmation.bindingKey()))
                    .findFirst()
                    .orElseGet(() -> {
                        values.add(confirmation);
                        return confirmation;
                    });
        }
    }
}
