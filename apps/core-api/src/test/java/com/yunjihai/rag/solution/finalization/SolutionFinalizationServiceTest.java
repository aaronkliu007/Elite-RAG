package com.yunjihai.rag.solution.finalization;

import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.solution.SolutionAuthorizationPort;
import com.yunjihai.rag.solution.domain.Claim;
import com.yunjihai.rag.solution.domain.ConcurrentSolutionUpdateException;
import com.yunjihai.rag.solution.domain.OutlineNode;
import com.yunjihai.rag.solution.domain.OutlineVersion;
import com.yunjihai.rag.solution.domain.RequirementCategory;
import com.yunjihai.rag.solution.domain.RequirementItem;
import com.yunjihai.rag.solution.domain.RequirementVersion;
import com.yunjihai.rag.solution.domain.SectionVersion;
import com.yunjihai.rag.solution.domain.SectionVersionKind;
import com.yunjihai.rag.solution.domain.Solution;
import com.yunjihai.rag.solution.domain.SolutionLifecycleException;
import com.yunjihai.rag.solution.generation.SectionVersionRepository;
import com.yunjihai.rag.solution.port.OutlineVersionRepository;
import com.yunjihai.rag.solution.port.SolutionRepository;
import com.yunjihai.rag.solution.verification.CitationVerdict;
import com.yunjihai.rag.solution.verification.ClaimVerification;
import com.yunjihai.rag.solution.verification.RiskConfirmation;
import com.yunjihai.rag.solution.verification.RiskConfirmationRepository;
import com.yunjihai.rag.solution.verification.SolutionRiskReport;
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

class SolutionFinalizationServiceTest {

    private static final UUID ACTOR_ID = id(1);
    private static final UUID SPACE_ID = id(2);
    private static final UUID SOLUTION_ID = id(3);
    private static final UUID REQUIREMENT_ID = id(10);
    private static final UUID OUTLINE_ID = id(20);
    private static final UUID SECTION_ID = id(30);
    private static final UUID SECTION_VERSION_ID = id(31);
    private static final UUID CLAIM_ID = id(40);
    private static final UUID EVIDENCE_ID = id(50);
    private static final Instant NOW = Instant.parse("2026-09-28T08:00:00Z");

    @Test
    void riskConfirmationRequiresAuthorizationAndNonBlankReason() {
        var denied = new Fixture((actor, space, solution, action, at) -> {
            throw new SecurityException("denied");
        });

        assertThatThrownBy(() -> denied.service.confirmRisk(
                ACTOR_ID, SOLUTION_ID, SECTION_VERSION_ID, CLAIM_ID, "客户已确认", NOW))
                .isInstanceOf(SecurityException.class);
        assertThat(denied.confirmations.values).isEmpty();

        var fixture = new Fixture(SolutionAuthorizationPort.allowAll());
        assertThatThrownBy(() -> fixture.service.confirmRisk(
                ACTOR_ID, SOLUTION_ID, SECTION_VERSION_ID, CLAIM_ID, "  ", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.confirmations.values).isEmpty();
    }

    @Test
    void riskConfirmationBindsExactSectionClaimEvidenceRequirementsAndOutline() {
        var fixture = new Fixture(SolutionAuthorizationPort.allowAll());

        var confirmation = fixture.service.confirmRisk(
                ACTOR_ID, SOLUTION_ID, SECTION_VERSION_ID, CLAIM_ID,
                "客户负责人确认该性能承诺", NOW.plusSeconds(1));

        var section = fixture.sections.findById(SECTION_VERSION_ID).orElseThrow();
        assertThat(confirmation.solutionId()).isEqualTo(SOLUTION_ID);
        assertThat(confirmation.sectionVersionId()).isEqualTo(SECTION_VERSION_ID);
        assertThat(confirmation.claimId()).isEqualTo(CLAIM_ID);
        assertThat(confirmation.evidenceIds()).containsExactly(EVIDENCE_ID);
        assertThat(confirmation.requirementVersionId()).isEqualTo(REQUIREMENT_ID);
        assertThat(confirmation.outlineVersionId()).isEqualTo(OUTLINE_ID);
        assertThat(confirmation.reason()).isEqualTo("客户负责人确认该性能承诺");
        assertThat(confirmation.matches(section)).isTrue();
        assertThat(fixture.confirmations.findBySolutionId(SOLUTION_ID))
                .containsExactly(confirmation);
        assertThat(fixture.audits).last().satisfies(command -> {
            assertThat(command.action()).isEqualTo(AuditAction.RISK_CONFIRM);
            assertThat(command.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(command.metadata()).contains(
                    Map.entry("sectionVersionId", SECTION_VERSION_ID.toString()),
                    Map.entry("claimId", CLAIM_ID.toString()),
                    Map.entry("evidenceCount", "1"));
            assertThat(command.metadata()).doesNotContainKeys("reason", "body", "content");
        });

        assertThat(confirmation.matches(section(
                SECTION_VERSION_ID, REQUIREMENT_ID, OUTLINE_ID,
                "声明正文已变化", Set.of(EVIDENCE_ID), CitationVerdict.HUMAN_CONFIRMATION_REQUIRED)))
                .isFalse();
        assertThat(confirmation.matches(section(
                SECTION_VERSION_ID, REQUIREMENT_ID, OUTLINE_ID,
                "系统支持100名用户", Set.of(id(999)), CitationVerdict.HUMAN_CONFIRMATION_REQUIRED)))
                .isFalse();
        assertThat(confirmation.matches(section(
                SECTION_VERSION_ID, id(998), OUTLINE_ID,
                "系统支持100名用户", Set.of(EVIDENCE_ID), CitationVerdict.HUMAN_CONFIRMATION_REQUIRED)))
                .isFalse();
        assertThat(confirmation.matches(section(
                SECTION_VERSION_ID, REQUIREMENT_ID, id(997),
                "系统支持100名用户", Set.of(EVIDENCE_ID), CitationVerdict.HUMAN_CONFIRMATION_REQUIRED)))
                .isFalse();
    }

    @Test
    void signaturesCannotOverridePartialUnsupportedOrAlreadySupportedVerdicts() {
        for (var verdict : List.of(
                CitationVerdict.PARTIAL,
                CitationVerdict.UNSUPPORTED,
                CitationVerdict.SUPPORTED)) {
            var fixture = new Fixture(SolutionAuthorizationPort.allowAll(), verdict);

            assertThatThrownBy(() -> fixture.service.confirmRisk(
                    ACTOR_ID, SOLUTION_ID, SECTION_VERSION_ID, CLAIM_ID, "尝试签字", NOW))
                    .isInstanceOf(SolutionLifecycleException.class);
            assertThat(fixture.confirmations.values).isEmpty();
        }
    }

    @Test
    void allFinalizationGatesFailBeforeTheAtomicCommit() {
        var unconfirmed = new Fixture(SolutionAuthorizationPort.allowAll());
        unconfirmed.solutions.values.put(
                SOLUTION_ID, Solution.create(SOLUTION_ID, SPACE_ID, ACTOR_ID, requirements(), NOW));
        assertGateFailure(unconfirmed, 0);

        var missingRequiredSection = new Fixture(SolutionAuthorizationPort.allowAll());
        missingRequiredSection.solutions.values.put(
                SOLUTION_ID,
                Solution.create(SOLUTION_ID, SPACE_ID, ACTOR_ID, requirements(), NOW)
                        .confirmRequirements(ACTOR_ID, NOW, 0)
                        .reviseOutline(outline(), 1)
                        .confirmOutline(ACTOR_ID, NOW, 2));
        assertGateFailure(missingRequiredSection, 3);

        var nonAdopted = new Fixture(SolutionAuthorizationPort.allowAll());
        nonAdopted.sections.values.put(
                SECTION_VERSION_ID,
                section(
                        SECTION_VERSION_ID, REQUIREMENT_ID, OUTLINE_ID,
                        "系统支持100名用户", Set.of(EVIDENCE_ID),
                        CitationVerdict.HUMAN_CONFIRMATION_REQUIRED, SectionVersionKind.MODEL_DRAFT));
        assertGateFailure(nonAdopted, 4);

        var unresolvedRisk = new Fixture(SolutionAuthorizationPort.allowAll());
        assertGateFailure(unresolvedRisk, 4);

        var stale = new Fixture(SolutionAuthorizationPort.allowAll());
        stale.service.confirmRisk(
                ACTOR_ID, SOLUTION_ID, SECTION_VERSION_ID, CLAIM_ID, "已确认", NOW.plusSeconds(1));
        assertGateFailure(stale, 99);
    }

    @Test
    void evidenceReauthorizationFailsClosedForSubsetUnknownNullExceptionAndConcurrentChange() {
        for (var mode : List.of("subset", "unknown", "null", "exception", "concurrent-change")) {
            var fixture = new Fixture(SolutionAuthorizationPort.allowAll());
            fixture.service.confirmRisk(
                    ACTOR_ID, SOLUTION_ID, SECTION_VERSION_ID, CLAIM_ID,
                    "已确认高风险承诺", NOW.plusSeconds(1));
            fixture.evidenceMode = mode;

            assertThatThrownBy(() -> fixture.service.finalizeSolution(
                    ACTOR_ID, SOLUTION_ID, 4, NOW.plusSeconds(2)))
                    .isInstanceOf(RuntimeException.class)
                    .satisfies(error -> assertThat(error.getMessage())
                            .doesNotContain("总体方案", "系统支持100名用户"));

            assertThat(fixture.store.commitCalls).isZero();
            assertThat(fixture.store.finalized).isEmpty();
            assertThat(fixture.solutions.values.get(SOLUTION_ID).finalizedVersionId()).isEmpty();
        }
    }

    @Test
    void completeEvidenceAccessCreatesAnImmutableDeterministicSnapshotAtomically() {
        var fixture = new Fixture(SolutionAuthorizationPort.allowAll());
        var confirmation = fixture.service.confirmRisk(
                ACTOR_ID, SOLUTION_ID, SECTION_VERSION_ID, CLAIM_ID,
                "已确认高风险承诺", NOW.plusSeconds(1));

        var finalized = fixture.service.finalizeSolution(
                ACTOR_ID, SOLUTION_ID, 4, NOW.plusSeconds(2));

        assertThat(fixture.store.commitCalls).isEqualTo(1);
        assertThat(fixture.store.finalized).containsExactly(finalized);
        assertThat(fixture.solutions.values.get(SOLUTION_ID).finalizedVersionId())
                .contains(finalized.id());
        assertThat(finalized.solutionId()).isEqualTo(SOLUTION_ID);
        assertThat(finalized.requirementVersionId()).isEqualTo(REQUIREMENT_ID);
        assertThat(finalized.outlineVersionId()).isEqualTo(OUTLINE_ID);
        assertThat(finalized.sectionVersionIds()).containsExactly(SECTION_VERSION_ID);
        assertThat(finalized.riskConfirmationIds()).containsExactly(confirmation.id());
        assertThat(finalized.contentHash()).matches("[0-9a-f]{64}");
        assertThat(fixture.authorizedEvidence).containsExactly(EVIDENCE_ID);
        assertThat(fixture.store.successAudits).singleElement()
                .satisfies(audit -> {
                    assertThat(audit.action()).isEqualTo(AuditAction.SOLUTION_FINALIZE);
                    assertThat(audit.metadata())
                            .contains(
                                    Map.entry("finalVersionId", finalized.id().toString()),
                                    Map.entry("evidenceCount", "1"),
                                    Map.entry("riskCount", "1"),
                                    Map.entry("contentHash", finalized.contentHash()))
                            .doesNotContainKeys("title", "body", "content", "excerpt");
                });
    }

    @Test
    void atomicCommitConflictLeavesVersionPointerAndSuccessAuditUnchanged() {
        var fixture = new Fixture(SolutionAuthorizationPort.allowAll());
        fixture.service.confirmRisk(
                ACTOR_ID, SOLUTION_ID, SECTION_VERSION_ID, CLAIM_ID,
                "已确认高风险承诺", NOW.plusSeconds(1));
        fixture.store.failCommit = true;

        assertThatThrownBy(() -> fixture.service.finalizeSolution(
                ACTOR_ID, SOLUTION_ID, 4, NOW.plusSeconds(2)))
                .isInstanceOf(ConcurrentSolutionUpdateException.class);

        assertThat(fixture.store.finalized).isEmpty();
        assertThat(fixture.store.successAudits).isEmpty();
        assertThat(fixture.solutions.values.get(SOLUTION_ID).finalizedVersionId()).isEmpty();
        assertThat(fixture.audits).last().satisfies(command -> {
            assertThat(command.action()).isEqualTo(AuditAction.SOLUTION_FINALIZE);
            assertThat(command.outcome()).isEqualTo(AuditOutcome.FAILED);
        });
    }

    @Test
    void atomicAuditFailureRollsBackAndFailureAuditFailureIsSuppressed() {
        var atomicAuditFailure = new Fixture(SolutionAuthorizationPort.allowAll());
        atomicAuditFailure.service.confirmRisk(
                ACTOR_ID, SOLUTION_ID, SECTION_VERSION_ID, CLAIM_ID,
                "已确认高风险承诺", NOW.plusSeconds(1));
        atomicAuditFailure.store.failAtomicAudit = true;

        assertThatThrownBy(() -> atomicAuditFailure.service.finalizeSolution(
                ACTOR_ID, SOLUTION_ID, 4, NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("atomic audit failed");
        assertThat(atomicAuditFailure.store.finalized).isEmpty();
        assertThat(atomicAuditFailure.store.successAudits).isEmpty();
        assertThat(atomicAuditFailure.solutions.values.get(SOLUTION_ID).finalizedVersionId()).isEmpty();

        var doubleFailure = new Fixture(SolutionAuthorizationPort.allowAll());
        doubleFailure.failureAuditException = new IllegalStateException("failure audit unavailable");

        assertThatThrownBy(() -> doubleFailure.service.finalizeSolution(
                ACTOR_ID, SOLUTION_ID, 4, NOW.plusSeconds(2)))
                .isInstanceOf(SolutionLifecycleException.class)
                .hasMessage("RISK_CONFIRMATION_REQUIRED")
                .satisfies(error -> assertThat(error.getSuppressed())
                        .singleElement()
                        .isSameAs(doubleFailure.failureAuditException));
    }

    private static void assertGateFailure(Fixture fixture, long expectedRevision) {
        assertThatThrownBy(() -> fixture.service.finalizeSolution(
                ACTOR_ID, SOLUTION_ID, expectedRevision, NOW.plusSeconds(2)))
                .isInstanceOf(RuntimeException.class);
        assertThat(fixture.store.commitCalls).isZero();
        assertThat(fixture.store.finalized).isEmpty();
        assertThat(fixture.solutions.values.get(SOLUTION_ID).finalizedVersionId()).isEmpty();
    }

    private static final class Fixture {
        private final InMemorySolutionRepository solutions = new InMemorySolutionRepository();
        private final InMemoryOutlineRepository outlines = new InMemoryOutlineRepository();
        private final InMemorySectionRepository sections = new InMemorySectionRepository();
        private final InMemoryRiskRepository confirmations = new InMemoryRiskRepository();
        private final InMemoryFinalizationStore store = new InMemoryFinalizationStore(solutions);
        private final List<AuditCommand> audits = new ArrayList<>();
        private String evidenceMode = "complete";
        private Set<UUID> authorizedEvidence = Set.of();
        private RuntimeException failureAuditException;
        private final SolutionFinalizationService service;

        private Fixture(SolutionAuthorizationPort authorization) {
            this(authorization, CitationVerdict.HUMAN_CONFIRMATION_REQUIRED);
        }

        private Fixture(SolutionAuthorizationPort authorization, CitationVerdict verdict) {
            var requirements = requirements();
            var outline = outline();
            var adopted = section(
                    SECTION_VERSION_ID, REQUIREMENT_ID, OUTLINE_ID,
                    "系统支持100名用户", Set.of(EVIDENCE_ID), verdict);
            var solution = Solution.create(SOLUTION_ID, SPACE_ID, ACTOR_ID, requirements, NOW)
                    .confirmRequirements(ACTOR_ID, NOW, 0)
                    .reviseOutline(outline, 1)
                    .confirmOutline(ACTOR_ID, NOW, 2)
                    .adoptSection(SECTION_ID, SECTION_VERSION_ID, 3);
            solutions.insert(solution);
            outlines.insert(outline);
            sections.insertIfAbsent(adopted);
            service = new SolutionFinalizationService(
                    solutions, outlines, sections, confirmations, authorization,
                    this::authorizeEvidence,
                    store,
                    command -> {
                        if (command.outcome() == AuditOutcome.FAILED && failureAuditException != null) {
                            throw failureAuditException;
                        }
                        audits.add(command);
                        return null;
                    });
        }

        private Set<UUID> authorizeEvidence(
                UUID userId, UUID spaceId, Set<UUID> evidenceIds, Instant at) {
            authorizedEvidence = Set.copyOf(evidenceIds);
            return switch (evidenceMode) {
                case "complete" -> evidenceIds;
                case "subset" -> Set.of();
                case "unknown" -> Set.of(EVIDENCE_ID, id(999));
                case "null" -> null;
                case "exception" -> throw new IllegalStateException("provider secret body");
                case "concurrent-change" -> {
                    var current = solutions.values.get(SOLUTION_ID);
                    var changed = current.adoptSection(SECTION_ID, id(998), current.revision());
                    solutions.replace(changed, current.revision());
                    yield evidenceIds;
                }
                default -> throw new IllegalArgumentException("unknown evidence mode");
            };
        }
    }

    private static RequirementVersion requirements() {
        return new RequirementVersion(
                REQUIREMENT_ID, 1,
                List.of(new RequirementItem(
                        id(11), RequirementCategory.UNKNOWN, "建设企业知识库", List.of())),
                ACTOR_ID, NOW, "首次需求");
    }

    private static OutlineVersion outline() {
        return new OutlineVersion(
                OUTLINE_ID, 1,
                List.of(new OutlineNode(
                        SECTION_ID, "总体方案", 1, 1, true,
                        "描述总体架构", "检索总体架构", Set.of())),
                ACTOR_ID, NOW);
    }

    private static SectionVersion section(
            UUID sectionVersionId,
            UUID requirementVersionId,
            UUID outlineVersionId,
            String claimText,
            Set<UUID> evidenceIds,
            CitationVerdict verdict) {
        return section(
                sectionVersionId, requirementVersionId, outlineVersionId,
                claimText, evidenceIds, verdict, SectionVersionKind.ADOPTED);
    }

    private static SectionVersion section(
            UUID sectionVersionId,
            UUID requirementVersionId,
            UUID outlineVersionId,
            String claimText,
            Set<UUID> evidenceIds,
            CitationVerdict verdict,
            SectionVersionKind kind) {
        var claim = new Claim(CLAIM_ID, claimText, true, evidenceIds, Set.of());
        return new SectionVersion(
                sectionVersionId, SOLUTION_ID, SECTION_ID, kind,
                "## 总体方案\n" + claimText,
                List.of(claim), evidenceIds, requirementVersionId, outlineVersionId,
                Optional.empty(), Optional.of(id(29)),
                kind == SectionVersionKind.MODEL_DRAFT ? Optional.empty() : Optional.of(ACTOR_ID),
                kind == SectionVersionKind.MODEL_DRAFT ? Optional.of("logical-model-v1") : Optional.empty(), NOW,
                new SolutionRiskReport(List.of(new ClaimVerification(
                        CLAIM_ID, verdict, "TEST_VERDICT"))));
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
    }

    private static final class InMemorySolutionRepository implements SolutionRepository {
        private final Map<UUID, Solution> values = new HashMap<>();

        @Override
        public Optional<Solution> findById(UUID solutionId) {
            return Optional.ofNullable(values.get(solutionId));
        }

        @Override
        public void insert(Solution solution) {
            values.put(solution.id(), solution);
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

    private static final class InMemoryOutlineRepository implements OutlineVersionRepository {
        private final Map<UUID, OutlineVersion> values = new HashMap<>();

        @Override
        public Optional<OutlineVersion> findById(UUID id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public void insert(OutlineVersion version) {
            values.put(version.id(), version);
        }
    }

    private static final class InMemorySectionRepository implements SectionVersionRepository {
        private final Map<UUID, SectionVersion> values = new HashMap<>();

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
            return Optional.empty();
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

    private static final class InMemoryFinalizationStore implements SolutionFinalizationStore {
        private final InMemorySolutionRepository solutions;
        private final List<FinalizedSolutionVersion> finalized = new ArrayList<>();
        private final List<AuditCommand> successAudits = new ArrayList<>();
        private int commitCalls;
        private boolean failCommit;
        private boolean failAtomicAudit;

        private InMemoryFinalizationStore(InMemorySolutionRepository solutions) {
            this.solutions = solutions;
        }

        @Override
        public void commit(
                Solution updated,
                long expectedRevision,
                FinalizedSolutionVersion version,
                AuditCommand successAudit) {
            commitCalls++;
            if (failCommit) {
                throw new ConcurrentSolutionUpdateException();
            }
            if (failAtomicAudit) {
                throw new IllegalStateException("atomic audit failed");
            }
            solutions.replace(updated, expectedRevision);
            finalized.add(version);
            successAudits.add(successAudit);
        }
    }
}
