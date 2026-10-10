package com.yunjihai.rag.solution.generation;

import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.solution.domain.Claim;
import com.yunjihai.rag.solution.domain.OutlineNode;
import com.yunjihai.rag.solution.domain.RequirementCategory;
import com.yunjihai.rag.solution.domain.RequirementItem;
import com.yunjihai.rag.solution.domain.RequirementSource;
import com.yunjihai.rag.solution.domain.RiskType;
import com.yunjihai.rag.solution.domain.SectionVersion;
import com.yunjihai.rag.solution.domain.SectionVersionKind;
import com.yunjihai.rag.solution.model.GeneratedOutlineProposal;
import com.yunjihai.rag.solution.model.GeneratedRequirementProposal;
import com.yunjihai.rag.solution.model.GeneratedSectionDraft;
import com.yunjihai.rag.solution.model.ModelScenario;
import com.yunjihai.rag.solution.model.PromptRef;
import com.yunjihai.rag.solution.model.SolutionModelRequest;
import com.yunjihai.rag.solution.model.SolutionModelPort;
import com.yunjihai.rag.solution.model.SolutionModelResponse;
import com.yunjihai.rag.solution.verification.CitationVerdict;
import com.yunjihai.rag.solution.verification.ClaimVerification;
import com.yunjihai.rag.solution.verification.SolutionRiskReport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationContractTest {

    private static final Instant NOW = Instant.parse("2026-09-28T08:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(60);

    @Test
    void modelRequestKeepsRuleRequirementsSectionTemplateAndEvidenceSeparated() {
        var requirements = new ArrayList<>(List.of(requirement()));
        var rules = new ArrayList<>(List.of("只陈述有证据支持的事实"));
        var evidence = new ArrayList<>(List.of(evidence()));
        var section = outlineNode();

        var request = new SolutionModelRequest(
                id(1),
                "client-request-1",
                ModelScenario.SECTION_GENERATION,
                new PromptRef("solution-section", "v1"),
                requirements,
                Optional.of(section),
                rules,
                evidence,
                DEADLINE);
        requirements.clear();
        rules.clear();
        evidence.clear();

        assertThat(request.confirmedRequirements()).containsExactly(requirement());
        assertThat(request.section()).contains(section);
        assertThat(request.templateRules()).containsExactly("只陈述有证据支持的事实");
        assertThat(request.evidence()).containsExactly(evidence());
        assertThat(request.deadline()).isEqualTo(DEADLINE);
        assertThatThrownBy(() -> request.confirmedRequirements().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.templateRules().add("越权规则"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void modelContractsDoNotLeakBodiesThroughToString() {
        var secretRequirement = "SECRET_REQUIREMENT_9f4a";
        var secretRule = "SECRET_RULE_4c2b";
        var secretEvidence = "SECRET_EVIDENCE_87e1";
        var request = new SolutionModelRequest(
                id(1),
                "client-request-1",
                ModelScenario.SECTION_GENERATION,
                new PromptRef("solution-section", "v1"),
                List.of(requirement(secretRequirement)),
                Optional.of(outlineNode()),
                List.of(secretRule),
                List.of(evidence(secretEvidence)),
                DEADLINE);
        var draft = new GeneratedSectionDraft(
                "SECRET_DRAFT_b521",
                List.of(claim()),
                Set.of(id(30)));
        var response = new SolutionModelResponse<>(
                GeneratedSectionDraft.class,
                draft,
                "logical-model-v1",
                new ModelUsage(100, 50, 1234, 800));

        assertThat(request.toString())
                .doesNotContain(secretRequirement, secretRule, secretEvidence);
        assertThat(response.toString()).doesNotContain("SECRET_DRAFT_b521");
        assertThat(draft.toString()).doesNotContain("SECRET_DRAFT_b521");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void modelResponseRejectsBodyThatDoesNotMatchDeclaredType() {
        var outline = new GeneratedOutlineProposal(List.of(generatedOutlineNode()));

        assertThatThrownBy(() -> new SolutionModelResponse(
                GeneratedSectionDraft.class,
                outline,
                "logical-model-v1",
                new ModelUsage(1, 1, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void proposalContractsOwnCollectionsAndModelPortReturnsTheRequestedType() {
        var requirements = new ArrayList<>(List.of(generatedRequirement()));
        var nodes = new ArrayList<>(List.of(generatedOutlineNode()));
        var requirementProposal = new GeneratedRequirementProposal(requirements);
        var outlineProposal = new GeneratedOutlineProposal(nodes);
        requirements.clear();
        nodes.clear();
        SolutionModelPort port = new SolutionModelPort() {
            @Override
            public <T> SolutionModelResponse<T> execute(
                    SolutionModelRequest request,
                    Class<T> responseType) {
                return new SolutionModelResponse<>(
                        responseType,
                        responseType.cast(outlineProposal),
                        "logical-model-v1",
                        new ModelUsage(10, 5, 20, 30));
            }
        };
        var request = new SolutionModelRequest(
                id(1), "client-request-1", ModelScenario.OUTLINE_GENERATION,
                new PromptRef("outline", "v1"), List.of(requirement()), Optional.empty(),
                List.of("至少一个必填章节"), List.of(), DEADLINE);

        var response = port.execute(request, GeneratedOutlineProposal.class);

        assertThat(requirementProposal.items()).containsExactly(generatedRequirement());
        assertThat(outlineProposal.nodes()).containsExactly(generatedOutlineNode());
        assertThat(response.body()).isSameAs(outlineProposal);
        assertThatThrownBy(() -> requirementProposal.items().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void generatedProposalsAndClaimsDoNotLeakBodiesThroughToString() {
        var requirementProposal = new GeneratedRequirementProposal(
                List.of(new GeneratedRequirementProposal.Item(
                        id(10), "GOAL", "SECRET_PROPOSAL_REQUIREMENT_91a2", List.of(requirementSource()))));
        var outlineProposal = new GeneratedOutlineProposal(List.of(new GeneratedOutlineProposal.Node(
                id(42), "SECRET_OUTLINE_TITLE_1b2c", 1, 1, true,
                "SECRET_OUTLINE_OBJECTIVE_3d4e", "SECRET_RETRIEVAL_HINT_5f6a", Set.of())));
        var claim = new Claim(
                id(50), "SECRET_CLAIM_TEXT_7b8c", true, Set.of(id(30)), Set.of());

        assertThat(requirementProposal.toString())
                .doesNotContain("SECRET_PROPOSAL_REQUIREMENT_91a2");
        assertThat(outlineProposal.toString())
                .doesNotContain(
                        "SECRET_OUTLINE_TITLE_1b2c",
                        "SECRET_OUTLINE_OBJECTIVE_3d4e",
                        "SECRET_RETRIEVAL_HINT_5f6a");
        assertThat(claim.toString()).doesNotContain("SECRET_CLAIM_TEXT_7b8c");
    }

    @Test
    void sectionVersionOwnsClaimsEvidenceAndVerificationImmutably() {
        var claims = new ArrayList<>(List.of(claim()));
        var evidenceIds = new HashSet<>(Set.of(id(30)));
        var report = report();

        var version = new SectionVersion(
                id(40), id(41), id(42), SectionVersionKind.MODEL_DRAFT,
                "## 总体方案\n支持100名用户。",
                claims, evidenceIds,
                id(43), id(44), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of("logical-model-v1"), NOW, report);
        claims.clear();
        evidenceIds.clear();

        assertThat(version.claims()).containsExactly(claim());
        assertThat(version.evidenceIds()).containsExactly(id(30));
        assertThat(version.verification()).isEqualTo(report);
        assertThatThrownBy(() -> version.claims().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> version.evidenceIds().add(id(31)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(version.toString()).doesNotContain("支持100名用户");
        assertThat(SectionVersionKind.values())
                .containsExactly(
                        SectionVersionKind.MODEL_DRAFT,
                        SectionVersionKind.HUMAN_EDIT,
                        SectionVersionKind.ADOPTED);
    }

    @Test
    void sectionVersionRequiresTheCorrectActorOrModelProducer() {
        assertThatThrownBy(() -> new SectionVersion(
                id(40), id(41), id(42), SectionVersionKind.MODEL_DRAFT,
                "模型稿", List.of(claim()), Set.of(id(30)),
                id(43), id(44), Optional.empty(), Optional.empty(),
                Optional.of(id(45)), Optional.empty(), NOW, report()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new SectionVersion(
                id(40), id(41), id(42), SectionVersionKind.ADOPTED,
                "采纳稿", List.of(claim()), Set.of(id(30)),
                id(43), id(44), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of("logical-model-v1"), NOW, report()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generationKeyFingerprintChangesWithGovernedInputAndRejectsClientKeyReuse() {
        var original = generationKey(id(60));
        var same = generationKey(id(60));
        var changedRequirement = new GenerationKey(
                "client-key-1", id(61), id(999), id(63), id(64), Optional.empty());
        var attempt = GenerationAttempt.start(id(70), original, NOW);

        assertThat(original.inputFingerprint()).isEqualTo(same.inputFingerprint());
        assertThat(original.inputFingerprint()).hasSize(64);
        assertThat(changedRequirement.inputFingerprint()).isNotEqualTo(original.inputFingerprint());
        assertThatThrownBy(() -> attempt.validateRequest(changedRequirement))
                .isInstanceOf(GenerationRequestConflictException.class);
    }

    @Test
    void generationAttemptFollowsTheNormalStateChainAndRecordsUsageOnce() {
        var usage = new ModelUsage(100, 50, 1234, 800);
        var attempt = GenerationAttempt.start(id(70), generationKey(id(60)), NOW);

        attempt = attempt.startRetrieval(NOW.plusSeconds(1));
        attempt = attempt.recordEvidence(id(71), Set.of(id(30)), NOW.plusSeconds(2));
        attempt = attempt.startVerification(usage, NOW.plusSeconds(3));
        attempt = attempt.succeed(id(72), NOW.plusSeconds(4));
        var completed = attempt;

        assertThat(completed.status()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(completed.revision()).isEqualTo(4);
        assertThat(completed.executionCount()).isEqualTo(1);
        assertThat(completed.modelUsage()).contains(usage);
        assertThat(completed.resultSectionVersionId()).contains(id(72));
        assertThat(completed.evidenceIds()).containsExactly(id(30));
        assertThatThrownBy(() -> completed.startRetrieval(NOW.plusSeconds(5)))
                .isInstanceOf(GenerationStateException.class);
    }

    @Test
    void nonRetryableEndStatesCannotTransitionAgain() {
        var retrieving = GenerationAttempt.start(id(70), generationKey(id(60)), NOW)
                .startRetrieval(NOW.plusSeconds(1));
        var insufficient = retrieving.insufficientEvidence(
                id(71), Set.of(), NOW.plusSeconds(2));
        var manual = retrieving.failManual("INVALID_MODEL_STRUCTURE", NOW.plusSeconds(2));
        var cancelled = retrieving.cancel(NOW.plusSeconds(2));

        assertThat(insufficient.status()).isEqualTo(GenerationStatus.INSUFFICIENT_EVIDENCE);
        assertThat(manual.status()).isEqualTo(GenerationStatus.FAILED_MANUAL);
        assertThat(cancelled.status()).isEqualTo(GenerationStatus.CANCELLED);
        for (var terminal : List.of(insufficient, manual, cancelled)) {
            assertThatThrownBy(() -> terminal.retry(NOW.plusSeconds(3)))
                    .isInstanceOf(GenerationStateException.class);
            assertThatThrownBy(() -> terminal.cancel(NOW.plusSeconds(3)))
                    .isInstanceOf(GenerationStateException.class);
        }
    }

    @Test
    void retryableFailureResumesSameLogicalAttemptFromLastCheckpoint() {
        var usage = new ModelUsage(100, 50, 1234, 800);
        var attempt = GenerationAttempt.start(id(70), generationKey(id(60)), NOW)
                .startRetrieval(NOW.plusSeconds(1))
                .recordEvidence(id(71), Set.of(id(30)), NOW.plusSeconds(2))
                .startVerification(usage, NOW.plusSeconds(3))
                .failRetryable("VERIFICATION_TIMEOUT", NOW.plusSeconds(4));

        var retried = attempt.retry(NOW.plusSeconds(5));

        assertThat(retried.id()).isEqualTo(attempt.id());
        assertThat(retried.status()).isEqualTo(GenerationStatus.VERIFYING);
        assertThat(retried.executionCount()).isEqualTo(2);
        assertThat(retried.modelUsage()).contains(usage);
        assertThatThrownBy(() -> retried.startVerification(
                new ModelUsage(1, 1, 1, 1), NOW.plusSeconds(6)))
                .isInstanceOf(GenerationStateException.class);
    }

    @Test
    void generationPolicyPinsEvidencePromptAndDeadlineDefaults() {
        var policy = GenerationPolicy.defaults();

        assertThat(policy.requestedEvidenceCount()).isEqualTo(8);
        assertThat(policy.minimumEvidenceCount()).isEqualTo(1);
        assertThat(policy.sectionPrompt()).isEqualTo(new PromptRef("solution-section", "v1"));
        assertThat(policy.modelTimeout()).isEqualTo(java.time.Duration.ofSeconds(60));
    }

    @Test
    void attemptStoreContractSupportsIdempotentInsertAndOptimisticReplace() {
        GenerationAttemptStore store = new InMemoryAttemptStore();
        var attempt = GenerationAttempt.start(id(70), generationKey(id(60)), NOW);

        assertThat(store.insertIfAbsent(attempt)).isSameAs(attempt);
        assertThat(store.insertIfAbsent(attempt)).isSameAs(attempt);
        assertThat(store.findByClientKey("client-key-1")).contains(attempt);
        var next = attempt.startRetrieval(NOW.plusSeconds(1));
        store.replace(next, attempt.revision());
        assertThat(store.findByClientKey("client-key-1")).contains(next);
    }

    @Test
    void canonicalAttemptConstructorRejectsStateThatBypassesTheStateMachine() {
        var started = GenerationAttempt.start(id(70), generationKey(id(60)), NOW);

        assertThatThrownBy(() -> new GenerationAttempt(
                started.id(), started.key(), started.inputFingerprint(),
                GenerationStatus.SUCCEEDED, 1, 1, NOW, NOW,
                Optional.empty(), Set.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GenerationAttempt(
                started.id(), started.key(), started.inputFingerprint(),
                GenerationStatus.PENDING, 1, 1, NOW, NOW,
                Optional.empty(), Set.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(GenerationStatus.RETRIEVING)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(GenerationStatus.values()).containsExactly(
                GenerationStatus.PENDING,
                GenerationStatus.RETRIEVING,
                GenerationStatus.GENERATING,
                GenerationStatus.VERIFYING,
                GenerationStatus.SUCCEEDED,
                GenerationStatus.INSUFFICIENT_EVIDENCE,
                GenerationStatus.FAILED_RETRYABLE,
                GenerationStatus.FAILED_MANUAL,
                GenerationStatus.CANCELLED);
    }

    private static RequirementItem requirement() {
        return requirement("客户要求支持100名用户");
    }

    private static RequirementItem requirement(String text) {
        return new RequirementItem(
                id(10),
                RequirementCategory.GOAL,
                text,
                List.of(requirementSource()));
    }

    private static RequirementSource requirementSource() {
        return new RequirementSource(
                new DocumentVersionRef(id(20), id(21)),
                new MarkdownLocator(List.of("客户需求"), "node-1"));
    }

    private static GeneratedRequirementProposal.Item generatedRequirement() {
        return new GeneratedRequirementProposal.Item(
                id(10), "GOAL", "客户要求支持100名用户", List.of(requirementSource()));
    }

    private static GeneratedOutlineProposal.Node generatedOutlineNode() {
        return new GeneratedOutlineProposal.Node(
                id(42), "总体方案", 1, 1, true,
                "描述总体架构", "检索总体架构", Set.of("PERFORMANCE_OR_SLA"));
    }

    private static OutlineNode outlineNode() {
        return new OutlineNode(
                id(42), "总体方案", 1, 1, true,
                "描述总体架构", "检索总体架构", Set.of(RiskType.PERFORMANCE_OR_SLA));
    }

    private static Claim claim() {
        return new Claim(
                id(50), "系统支持100名用户", true,
                Set.of(id(30)), Set.of(RiskType.PERFORMANCE_OR_SLA));
    }

    private static Evidence evidence() {
        return evidence("客户要求系统支持100名用户");
    }

    private static Evidence evidence(String excerpt) {
        return new Evidence(
                id(2), id(30), id(20), id(21), "node-1", "客户需求", excerpt, 0.9,
                new MarkdownLocator(List.of("客户需求"), "node-1"));
    }

    private static SolutionRiskReport report() {
        return new SolutionRiskReport(List.of(new ClaimVerification(
                id(50), CitationVerdict.HUMAN_CONFIRMATION_REQUIRED,
                "HIGH_RISK_CONFIRMATION_REQUIRED")));
    }

    private static GenerationKey generationKey(UUID requirementVersionId) {
        return new GenerationKey(
                "client-key-1",
                id(61),
                requirementVersionId,
                id(63),
                id(64),
                Optional.empty());
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
    }

    private static final class InMemoryAttemptStore implements GenerationAttemptStore {
        private final HashMap<String, GenerationAttempt> attempts = new HashMap<>();

        @Override
        public Optional<GenerationAttempt> findByClientKey(String clientKey) {
            return Optional.ofNullable(attempts.get(clientKey));
        }

        @Override
        public GenerationAttemptRegistration register(GenerationAttempt attempt) {
            var existing = attempts.putIfAbsent(attempt.key().clientKey(), attempt);
            return new GenerationAttemptRegistration(
                    existing == null ? attempt : existing, existing == null);
        }

        @Override
        public void replace(GenerationAttempt next, long expectedRevision) {
            attempts.compute(next.key().clientKey(), (key, current) -> {
                if (current == null || current.revision() != expectedRevision) {
                    throw new GenerationRequestConflictException();
                }
                return next;
            });
        }
    }
}
