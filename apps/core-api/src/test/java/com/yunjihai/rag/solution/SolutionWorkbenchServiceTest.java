package com.yunjihai.rag.solution;

import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.solution.domain.ConcurrentSolutionUpdateException;
import com.yunjihai.rag.solution.domain.OutlineNode;
import com.yunjihai.rag.solution.domain.OutlineVersion;
import com.yunjihai.rag.solution.domain.RequirementCategory;
import com.yunjihai.rag.solution.domain.RequirementItem;
import com.yunjihai.rag.solution.domain.RequirementSource;
import com.yunjihai.rag.solution.domain.RequirementVersion;
import com.yunjihai.rag.solution.domain.Solution;
import com.yunjihai.rag.solution.domain.SolutionName;
import com.yunjihai.rag.solution.domain.SolutionLifecycleException;
import com.yunjihai.rag.solution.domain.SolutionPhase;
import com.yunjihai.rag.solution.model.SolutionModelPort;
import com.yunjihai.rag.solution.model.SolutionModelRequest;
import com.yunjihai.rag.solution.model.SolutionModelResponse;
import com.yunjihai.rag.solution.model.GeneratedOutlineProposal;
import com.yunjihai.rag.solution.model.GeneratedRequirementProposal;
import com.yunjihai.rag.solution.generation.ModelUsage;
import com.yunjihai.rag.solution.port.OutlineVersionRepository;
import com.yunjihai.rag.solution.port.RequirementVersionRepository;
import com.yunjihai.rag.solution.port.SolutionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SolutionWorkbenchServiceTest {

    private static final UUID ACTOR_ID = id(1);
    private static final UUID SPACE_ID = id(2);
    private static final UUID SOLUTION_ID = id(3);
    private static final Instant NOW = Instant.parse("2026-09-28T08:00:00Z");

    @Test
    void authorizationDenialAndFailureHappenBeforeRepositoryReadOrModelCall() {
        for (var denied : List.of(
                new SecurityException("denied"),
                new IllegalStateException("authorization unavailable"))) {
            var fixture = new Fixture((actorId, spaceId, solutionId, action, at) -> {
                throw denied;
            });
            var command = new SolutionWorkbenchService.ConfirmRequirementsCommand(
                    ACTOR_ID, SPACE_ID, SOLUTION_ID, id(10), 0, id(90), NOW);

            assertThatThrownBy(() -> fixture.service.confirmRequirements(command))
                    .isSameAs(denied);
            assertThat(fixture.solutions.findCalls).isZero();
            assertThat(fixture.model.calls).isZero();
            assertThat(fixture.audit).singleElement().satisfies(event -> {
                assertThat(event.outcome()).isIn(
                        com.yunjihai.rag.audit.AuditOutcome.DENIED,
                        com.yunjihai.rag.audit.AuditOutcome.FAILED);
                assertThat(event.metadata()).containsEntry("errorCode", "AUTHORIZATION_FAILED");
                assertThat(event.metadata().values()).noneMatch(value -> value.contains(denied.getMessage()));
            });
        }
    }

    @Test
    void modelFailuresAndInvalidEnumsExposeOnlyStableErrorCodes() {
        var unavailable = solutionWithInitialRequirements();
        unavailable.model.failure = new IllegalStateException("provider-secret-token");

        assertThatThrownBy(() -> unavailable.service.proposeRequirements(
                new SolutionWorkbenchService.ProposeRequirementsCommand(
                        ACTOR_ID, SPACE_ID, SOLUTION_ID, id(11), "client-requirements-1",
                        "模型提取", 0, id(90), NOW.plusSeconds(1))))
                .isInstanceOf(SolutionLifecycleException.class)
                .hasMessage("MODEL_PROPOSAL_FAILED")
                .hasNoCause()
                .satisfies(error -> assertThat(error.getSuppressed()).isEmpty());

        var invalid = solutionWithInitialRequirements();
        invalid.model.respond(new GeneratedRequirementProposal(List.of(
                generatedRequirement(id(101), "provider-secret-enum", List.of(source())))));

        assertThatThrownBy(() -> invalid.service.proposeRequirements(
                new SolutionWorkbenchService.ProposeRequirementsCommand(
                        ACTOR_ID, SPACE_ID, SOLUTION_ID, id(11), "client-requirements-2",
                        "模型提取", 0, id(91), NOW.plusSeconds(1))))
                .isInstanceOf(SolutionLifecycleException.class)
                .hasMessage("MODEL_PROPOSAL_INVALID")
                .hasNoCause()
                .satisfies(error -> assertThat(error.getSuppressed()).isEmpty());
    }

    @Test
    void unconfirmedRequirementsAndStaleRevisionStopBeforeWriteOrModelCall() {
        var fixture = new Fixture(SolutionAuthorizationPort.allowAll());
        var initial = requirements(id(10), 1, "首次需求");
        fixture.solutions.insert(Solution.create(SOLUTION_ID, SPACE_ID, ACTOR_ID, initial, NOW));
        fixture.requirements.insert(initial);
        var outline = outline(id(20), 1);

        assertThatThrownBy(() -> fixture.service.reviseOutline(
                new SolutionWorkbenchService.ReviseOutlineCommand(
                        ACTOR_ID, SPACE_ID, SOLUTION_ID, outline,
                        0, id(90), NOW.plusSeconds(1))))
                .isInstanceOf(SolutionLifecycleException.class);
        assertThat(fixture.solutions.replaceCalls).isZero();
        assertThat(fixture.model.calls).isZero();

        assertThatThrownBy(() -> fixture.service.reviseRequirements(
                new SolutionWorkbenchService.ReviseRequirementsCommand(
                        ACTOR_ID, SPACE_ID, SOLUTION_ID,
                        requirements(id(11), 2, "修订需求"),
                        99, id(91), NOW.plusSeconds(2))))
                .isInstanceOf(ConcurrentSolutionUpdateException.class);
        assertThat(fixture.solutions.replaceCalls).isZero();
        assertThat(fixture.model.calls).isZero();
    }

    @Test
    void confirmationOnlyAppliesToTheExactCurrentVersion() {
        var fixture = new Fixture(SolutionAuthorizationPort.allowAll());
        var initial = requirements(id(10), 1, "首次需求");
        fixture.solutions.insert(Solution.create(SOLUTION_ID, SPACE_ID, ACTOR_ID, initial, NOW));
        fixture.requirements.insert(initial);

        assertThatThrownBy(() -> fixture.service.confirmRequirements(
                new SolutionWorkbenchService.ConfirmRequirementsCommand(
                        ACTOR_ID, SPACE_ID, SOLUTION_ID, id(999),
                        0, id(90), NOW.plusSeconds(1))))
                .isInstanceOf(SolutionLifecycleException.class);

        assertThat(fixture.solutions.findById(SOLUTION_ID).orElseThrow().phase())
                .isEqualTo(SolutionPhase.REQUIREMENTS_DRAFT);
        assertThat(fixture.solutions.replaceCalls).isZero();
    }

    @Test
    void manualPathPersistsImmutableVersionsAndConfirmsRequirementsThenOutline() {
        var fixture = new Fixture(SolutionAuthorizationPort.allowAll());
        var initial = requirements(id(10), 1, "首次需求");

        var solution = fixture.service.createSolution(
                new SolutionWorkbenchService.CreateSolutionCommand(
                        ACTOR_ID, SPACE_ID, SOLUTION_ID, initial,
                        0, id(90), NOW));
        solution = fixture.service.confirmRequirements(
                new SolutionWorkbenchService.ConfirmRequirementsCommand(
                        ACTOR_ID, SPACE_ID, SOLUTION_ID, initial.id(),
                        solution.revision(), id(91), NOW.plusSeconds(1)));
        var outline = outline(id(20), 1);
        solution = fixture.service.reviseOutline(
                new SolutionWorkbenchService.ReviseOutlineCommand(
                        ACTOR_ID, SPACE_ID, SOLUTION_ID, outline,
                        solution.revision(), id(92), NOW.plusSeconds(2)));
        solution = fixture.service.confirmOutline(
                new SolutionWorkbenchService.ConfirmOutlineCommand(
                        ACTOR_ID, SPACE_ID, SOLUTION_ID, outline.id(),
                        solution.revision(), id(93), NOW.plusSeconds(3)));

        assertThat(solution.phase()).isEqualTo(SolutionPhase.OUTLINE_CONFIRMED);
        assertThat(fixture.requirements.findById(initial.id())).contains(initial);
        assertThat(fixture.outlines.findById(outline.id())).contains(outline);
        assertThat(fixture.audit).hasSize(4);
        assertThat(fixture.audit).extracting(AuditCommand::action)
                .containsExactly(
                        com.yunjihai.rag.audit.AuditAction.SOLUTION_CREATE,
                        com.yunjihai.rag.audit.AuditAction.REQUIREMENTS_CONFIRM,
                        com.yunjihai.rag.audit.AuditAction.EDIT,
                        com.yunjihai.rag.audit.AuditAction.OUTLINE_CONFIRM);
        assertThat(fixture.authorization.actions)
                .containsExactly(
                        SolutionAction.EDIT_REQUIREMENTS,
                        SolutionAction.CONFIRM_REQUIREMENTS,
                        SolutionAction.EDIT_OUTLINE,
                        SolutionAction.CONFIRM_OUTLINE);
    }

    @Test
    void createSolutionPreservesUserSuppliedName() {
        var fixture = new Fixture(SolutionAuthorizationPort.allowAll());
        var initial = requirements(id(110), 1, "首次需求");

        var solution = fixture.service.createSolution(new SolutionWorkbenchService.CreateSolutionCommand(
                ACTOR_ID, SPACE_ID, SOLUTION_ID, SolutionName.of("制造业方案"), initial,
                0, id(111), NOW));

        assertThat(solution.name().value()).isEqualTo("制造业方案");
    }

    @Test
    void auditFailureDoesNotReturnAReportedSuccess() {
        var fixture = new Fixture(SolutionAuthorizationPort.allowAll(), command -> {
            throw new IllegalStateException("audit unavailable");
        });
        var initial = requirements(id(10), 1, "首次需求");

        assertThatThrownBy(() -> fixture.service.createSolution(
                new SolutionWorkbenchService.CreateSolutionCommand(
                        ACTOR_ID, SPACE_ID, SOLUTION_ID, initial,
                        0, id(90), NOW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
        assertThat(fixture.solutions.findById(SOLUTION_ID)).isPresent();
        assertThat(fixture.requirements.findById(initial.id())).isPresent();
    }

    @Test
    void authorizationActionsIncludeIndependentOutlineEditing() {
        assertThat(SolutionAction.values()).containsExactly(
                SolutionAction.EDIT_REQUIREMENTS,
                SolutionAction.EDIT_OUTLINE,
                SolutionAction.CONFIRM_REQUIREMENTS,
                SolutionAction.CONFIRM_OUTLINE,
                SolutionAction.ADOPT_SECTION,
                SolutionAction.CONFIRM_RISK,
                SolutionAction.FINALIZE);
    }

    @Test
    void requirementProposalIsFullyValidatedBeforeReplacingTheCurrentVersion() {
        for (var proposal : List.of(
                new GeneratedRequirementProposal(List.of(
                        new GeneratedRequirementProposal.Item(id(101), "", "需求", List.of(source())))),
                new GeneratedRequirementProposal(List.of(
                        generatedRequirement(id(101), "GOAL", List.of(source())),
                        generatedRequirement(id(101), "GOAL", List.of(source())))),
                new GeneratedRequirementProposal(List.of(
                        generatedRequirement(id(101), "GOAL", List.of()))))) {
            var fixture = solutionWithInitialRequirements();
            fixture.model.respond(proposal);

            assertThatThrownBy(() -> fixture.service.proposeRequirements(
                    new SolutionWorkbenchService.ProposeRequirementsCommand(
                            ACTOR_ID, SPACE_ID, SOLUTION_ID, id(11), "client-requirements-1",
                            "模型提取", 0, id(90), NOW.plusSeconds(1))))
                    .isInstanceOf(SolutionLifecycleException.class)
                    .hasMessage("MODEL_PROPOSAL_INVALID");

            assertThat(fixture.solutions.findById(SOLUTION_ID).orElseThrow().currentRequirementVersionId())
                    .isEqualTo(id(10));
            assertThat(fixture.requirements.findById(id(11))).isEmpty();
            assertThat(fixture.solutions.replaceCalls).isZero();
        }
    }

    @Test
    void validRequirementProposalUsesPinnedPromptAndPersistsOnlyTheCompleteVersion() {
        var fixture = solutionWithInitialRequirements();
        fixture.model.respond(new GeneratedRequirementProposal(List.of(
                generatedRequirement(id(101), "GOAL", List.of(source())))));

        var updated = fixture.service.proposeRequirements(
                new SolutionWorkbenchService.ProposeRequirementsCommand(
                        ACTOR_ID, SPACE_ID, SOLUTION_ID, id(11), "client-requirements-1",
                        "模型提取", 0, id(90), NOW.plusSeconds(1)));

        assertThat(updated.currentRequirementVersionId()).isEqualTo(id(11));
        assertThat(fixture.requirements.findById(id(11))).isPresent();
        assertThat(fixture.model.lastRequest.scenario()).isEqualTo(
                com.yunjihai.rag.solution.model.ModelScenario.REQUIREMENTS_EXTRACTION);
        assertThat(fixture.model.lastRequest.prompt()).isEqualTo(
                new com.yunjihai.rag.solution.model.PromptRef("requirements", "v1"));
        assertThat(fixture.model.lastRequest.idempotencyKey()).isEqualTo("client-requirements-1");
        assertThat(fixture.audit).last().satisfies(command -> {
            assertThat(command.metadata()).contains(
                    Map.entry("solutionId", SOLUTION_ID.toString()),
                    Map.entry("requirementVersionId", id(11).toString()),
                    Map.entry("promptName", "requirements"),
                    Map.entry("promptVersion", "v1"),
                    Map.entry("modelId", "logical-model-v1"),
                    Map.entry("status", "SUCCEEDED"),
                    Map.entry("itemCount", "1"),
                    Map.entry("inputTokens", "10"),
                    Map.entry("outputTokens", "5"),
                    Map.entry("costMicros", "20"),
                    Map.entry("durationMs", "30"));
            assertThat(command.metadata()).doesNotContainKeys("body", "text", "content");
        });
    }

    @Test
    void requirementProposalCannotIntroduceSourcesAbsentFromTheInput() {
        var forgedSources = List.of(
                new RequirementSource(new DocumentVersionRef(id(202), id(203)),
                        new MarkdownLocator(List.of("客户需求"), "node-1")),
                new RequirementSource(new DocumentVersionRef(id(200), id(201)),
                        new MarkdownLocator(List.of("客户需求"), "node-2")));
        for (var forged : forgedSources) {
            var fixture = solutionWithInitialRequirements();
            fixture.model.respond(new GeneratedRequirementProposal(List.of(
                    generatedRequirement(id(101), "GOAL", List.of(forged)))));

            assertThatThrownBy(() -> fixture.service.proposeRequirements(
                    new SolutionWorkbenchService.ProposeRequirementsCommand(
                            ACTOR_ID, SPACE_ID, SOLUTION_ID, id(11), "client-requirements-1",
                            "模型提取", 0, id(90), NOW.plusSeconds(1))))
                    .isInstanceOf(SolutionLifecycleException.class)
                    .hasMessage("MODEL_PROPOSAL_INVALID");
            assertThat(fixture.solutions.findById(SOLUTION_ID).orElseThrow().currentRequirementVersionId())
                    .isEqualTo(id(10));
            assertThat(fixture.requirements.findById(id(11))).isEmpty();
            assertThat(fixture.solutions.replaceCalls).isZero();
        }
    }

    @Test
    void outlineProposalIsFullyValidatedBeforeReplacingTheCurrentVersion() {
        for (var proposal : List.of(
                new GeneratedOutlineProposal(List.of(
                        generatedOutline(id(30), 1, 1, false))),
                new GeneratedOutlineProposal(List.of(
                        generatedOutline(id(30), 1, 1, true),
                        generatedOutline(id(30), 1, 2, true))),
                new GeneratedOutlineProposal(List.of(
                        generatedOutline(id(30), 5, 1, true))),
                new GeneratedOutlineProposal(List.of(
                        generatedOutline(id(30), 1, 2, true))))) {
            var fixture = solutionWithConfirmedRequirements();
            fixture.model.respond(proposal);

            assertThatThrownBy(() -> fixture.service.proposeOutline(
                    new SolutionWorkbenchService.ProposeOutlineCommand(
                            ACTOR_ID, SPACE_ID, SOLUTION_ID, id(20), "client-outline-1",
                            1, id(90), NOW.plusSeconds(2))))
                    .isInstanceOf(SolutionLifecycleException.class)
                    .hasMessage("MODEL_PROPOSAL_INVALID");

            assertThat(fixture.solutions.findById(SOLUTION_ID).orElseThrow().currentOutlineVersionId())
                    .isEmpty();
            assertThat(fixture.outlines.findById(id(20))).isEmpty();
            assertThat(fixture.solutions.replaceCalls).isEqualTo(1);
        }
    }

    @Test
    void validOutlineProposalUsesConfirmedRequirementsAndPinnedPrompt() {
        var fixture = solutionWithConfirmedRequirements();
        fixture.model.respond(new GeneratedOutlineProposal(List.of(
                generatedOutline(id(30), 1, 1, true))));

        var updated = fixture.service.proposeOutline(
                new SolutionWorkbenchService.ProposeOutlineCommand(
                        ACTOR_ID, SPACE_ID, SOLUTION_ID, id(20), "client-outline-1",
                        1, id(90), NOW.plusSeconds(2)));

        assertThat(updated.currentOutlineVersionId()).contains(id(20));
        assertThat(fixture.outlines.findById(id(20))).isPresent();
        assertThat(fixture.model.lastRequest.confirmedRequirements())
                .containsExactlyElementsOf(requirements(id(10), 1, "首次需求").items());
        assertThat(fixture.model.lastRequest.prompt()).isEqualTo(
                new com.yunjihai.rag.solution.model.PromptRef("outline", "v1"));
    }

    private static Fixture solutionWithInitialRequirements() {
        var fixture = new Fixture(SolutionAuthorizationPort.allowAll());
        var initial = requirements(id(10), 1, "首次需求");
        fixture.solutions.insert(Solution.create(SOLUTION_ID, SPACE_ID, ACTOR_ID, initial, NOW));
        fixture.requirements.insert(initial);
        return fixture;
    }

    private static Fixture solutionWithConfirmedRequirements() {
        var fixture = solutionWithInitialRequirements();
        fixture.service.confirmRequirements(new SolutionWorkbenchService.ConfirmRequirementsCommand(
                ACTOR_ID, SPACE_ID, SOLUTION_ID, id(10), 0, id(89), NOW.plusSeconds(1)));
        return fixture;
    }

    private static GeneratedRequirementProposal.Item generatedRequirement(
            UUID itemId, String category, List<RequirementSource> sources) {
        return new GeneratedRequirementProposal.Item(itemId, category, "建设企业知识库", sources);
    }

    private static GeneratedOutlineProposal.Node generatedOutline(
            UUID sectionId, int level, int order, boolean required) {
        return new GeneratedOutlineProposal.Node(
                sectionId, "总体方案", level, order, required,
                "描述总体架构", "检索总体架构", Set.of());
    }

    private static RequirementSource source() {
        return new RequirementSource(
                new DocumentVersionRef(id(200), id(201)),
                new MarkdownLocator(List.of("客户需求"), "node-1"));
    }

    private static RequirementVersion requirements(UUID versionId, long number, String reason) {
        return new RequirementVersion(
                versionId,
                number,
                List.of(new RequirementItem(
                        id(100 + (int) number), RequirementCategory.GOAL, "建设企业知识库",
                        List.of(new RequirementSource(
                                new DocumentVersionRef(id(200), id(201)),
                                new MarkdownLocator(List.of("客户需求"), "node-1"))))),
                ACTOR_ID,
                NOW,
                reason);
    }

    private static OutlineVersion outline(UUID versionId, long number) {
        return new OutlineVersion(
                versionId,
                number,
                List.of(new OutlineNode(
                        id(30), "总体方案", 1, 1, true,
                        "描述总体架构", "检索总体架构", Set.of())),
                ACTOR_ID,
                NOW);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
    }

    private static final class Fixture {
        private final InMemorySolutionRepository solutions = new InMemorySolutionRepository();
        private final InMemoryRequirementRepository requirements = new InMemoryRequirementRepository();
        private final InMemoryOutlineRepository outlines = new InMemoryOutlineRepository();
        private final RecordingModelPort model = new RecordingModelPort();
        private final RecordingAuthorization authorization;
        private final List<AuditCommand> audit = new ArrayList<>();
        private final SolutionWorkbenchService service;

        private Fixture(SolutionAuthorizationPort authorization) {
            this(authorization, command -> null);
        }

        private Fixture(SolutionAuthorizationPort authorization, AuditSink auditSink) {
            this.authorization = new RecordingAuthorization(authorization);
            AuditSink sink = auditSink == null ? command -> null : command -> {
                audit.add(command);
                return auditSink.record(command);
            };
            service = new SolutionWorkbenchService(
                    solutions, requirements, outlines,
                    this.authorization, model, sink);
        }
    }

    private static final class RecordingAuthorization implements SolutionAuthorizationPort {
        private final SolutionAuthorizationPort delegate;
        private final List<SolutionAction> actions = new ArrayList<>();

        private RecordingAuthorization(SolutionAuthorizationPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public void require(
                UUID actorId,
                UUID spaceId,
                UUID solutionId,
                SolutionAction action,
                Instant at) {
            actions.add(action);
            delegate.require(actorId, spaceId, solutionId, action, at);
        }
    }

    private static final class RecordingModelPort implements SolutionModelPort {
        private int calls;
        private Object nextBody;
        private RuntimeException failure;
        private SolutionModelRequest lastRequest;

        private void respond(Object body) {
            nextBody = body;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> SolutionModelResponse<T> execute(SolutionModelRequest request, Class<T> responseType) {
            calls++;
            lastRequest = request;
            if (failure != null) {
                throw failure;
            }
            if (nextBody == null) {
                throw new AssertionError("model must not be called");
            }
            return new SolutionModelResponse<>(
                    responseType,
                    (T) nextBody,
                    "logical-model-v1",
                    new ModelUsage(10, 5, 20, 30));
        }
    }

    private static final class InMemorySolutionRepository implements SolutionRepository {
        private final HashMap<UUID, Solution> values = new HashMap<>();
        private int findCalls;
        private int replaceCalls;

        @Override
        public Optional<Solution> findById(UUID solutionId) {
            findCalls++;
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
            replaceCalls++;
            values.compute(solution.id(), (id, current) -> {
                if (current == null || current.revision() != expectedRevision) {
                    throw new ConcurrentSolutionUpdateException();
                }
                return solution;
            });
        }
    }

    private static final class InMemoryRequirementRepository implements RequirementVersionRepository {
        private final HashMap<UUID, RequirementVersion> values = new HashMap<>();

        @Override
        public Optional<RequirementVersion> findById(UUID requirementVersionId) {
            return Optional.ofNullable(values.get(requirementVersionId));
        }

        @Override
        public void insert(RequirementVersion requirementVersion) {
            if (values.putIfAbsent(requirementVersion.id(), requirementVersion) != null) {
                throw new IllegalStateException("duplicate requirement version");
            }
        }
    }

    private static final class InMemoryOutlineRepository implements OutlineVersionRepository {
        private final HashMap<UUID, OutlineVersion> values = new HashMap<>();

        @Override
        public Optional<OutlineVersion> findById(UUID outlineVersionId) {
            return Optional.ofNullable(values.get(outlineVersionId));
        }

        @Override
        public void insert(OutlineVersion outlineVersion) {
            if (values.putIfAbsent(outlineVersion.id(), outlineVersion) != null) {
                throw new IllegalStateException("duplicate outline version");
            }
        }
    }
}
