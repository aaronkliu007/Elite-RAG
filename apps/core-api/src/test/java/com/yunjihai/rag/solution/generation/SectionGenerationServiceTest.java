package com.yunjihai.rag.solution.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.RetrievalService;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.EvidencePack;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.domain.RetrievalQuery;
import com.yunjihai.rag.retrieval.domain.RetrievalStats;
import com.yunjihai.rag.solution.SolutionAction;
import com.yunjihai.rag.solution.SolutionAuthorizationPort;
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
import com.yunjihai.rag.solution.model.GeneratedSectionDraft;
import com.yunjihai.rag.solution.model.SolutionModelPort;
import com.yunjihai.rag.solution.model.SolutionModelRequest;
import com.yunjihai.rag.solution.model.SolutionModelResponse;
import com.yunjihai.rag.solution.port.OutlineVersionRepository;
import com.yunjihai.rag.solution.port.RequirementVersionRepository;
import com.yunjihai.rag.solution.port.SolutionRepository;
import com.yunjihai.rag.solution.verification.CitationVerdict;
import com.yunjihai.rag.solution.verification.ClaimEvidenceInput;
import com.yunjihai.rag.solution.verification.ClaimVerification;
import com.yunjihai.rag.solution.verification.SectionVerificationService;
import com.yunjihai.rag.solution.verification.SolutionRiskReport;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SectionGenerationServiceTest {

    private static final UUID ACTOR_ID = id(1);
    private static final UUID SPACE_ID = id(2);
    private static final UUID SOLUTION_ID = id(3);
    private static final UUID REQUIREMENT_ID = id(10);
    private static final UUID OUTLINE_ID = id(20);
    private static final UUID SECTION_ID = id(30);
    private static final UUID SECOND_SECTION_ID = id(31);
    private static final UUID EVIDENCE_ID = id(40);
    private static final UUID ATTEMPT_ID = id(50);
    private static final UUID SECTION_VERSION_ID = id(60);
    private static final UUID RETRIEVAL_REQUEST_ID = id(70);
    private static final Instant NOW = Instant.parse("2026-09-28T08:00:00Z");

    @Test
    void syntheticGoldenCasesExerciseGenerationAndAdoptionWithDeterministicTestDoubles() throws Exception {
        var golden = Path.of("..", "..", "evaluation", "generation", "golden-v1.jsonl");
        var mapper = new ObjectMapper();
        var cases = Files.readAllLines(golden).stream().filter(line -> !line.isBlank())
                .map(line -> {
                    try { return mapper.readTree(line); }
                    catch (Exception invalid) { throw new IllegalArgumentException(invalid); }
                }).collect(java.util.stream.Collectors.toMap(row -> row.path("id").asText(), row -> row));

        for (var id : List.of("GEN-001", "GEN-002", "GEN-003", "GEN-004", "GEN-005",
                "GEN-006", "GEN-007", "GEN-008", "GEN-009", "GEN-010", "GEN-011", "GEN-012")) {
            var row = cases.get(id);
            assertThat(row).as(id).isNotNull();
            var candidate = row.path("candidateClaim").asText();
            assertThat(candidate).as(id).isNotBlank();
            if (!row.path("expectedClaim").isNull()) {
                assertThat(candidate).as(id).isEqualTo(row.path("expectedClaim").asText());
            }
            var fixture = Fixture.ready(row.path("requirement").asText());
            var evidenceSpace = id.equals("GEN-008") ? id(999) : SPACE_ID;
            var selected = new ArrayList<Evidence>();
            for (var index = 0; index < row.path("evidence").size(); index++) {
                selected.add(new Evidence(evidenceSpace, id(40 + index), id(140 + index), id(240 + index),
                        "node-" + index, "合成资料", row.path("evidence").get(index).asText(), 0.9,
                        new MarkdownLocator(List.of("合成资料"), "node-" + index)));
            }
            fixture.evidence = List.copyOf(selected);
            var semanticVerdict = row.path("semanticVerdict").asText("");
            if (!semanticVerdict.isBlank()) {
                fixture.semanticVerdict = CitationVerdict.valueOf(semanticVerdict);
                fixture.semanticReasonCode = row.path("semanticReason").asText();
            }
            var risks = row.path("riskType").isNull() ? Set.<RiskType>of()
                    : Set.of(RiskType.valueOf(row.path("riskType").asText()));
            var citedIds = fixture.evidence.stream().map(Evidence::evidenceId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            var claim = new Claim(id(80), candidate, true, citedIds, risks);
            fixture.modelDraft = new GeneratedSectionDraft(
                    "## 总体方案\n" + candidate, List.of(claim), citedIds);
            if (id.equals("GEN-008")) {
                assertThat(row.path("expectedDecision").asText()).isEqualTo("REFUSE");
                assertThatThrownBy(() -> fixture.service.generate(command(SECTION_ID)))
                        .as(id).isInstanceOf(SecurityException.class);
                assertThat(fixture.modelCalls).as(id).isZero();
                assertThat(fixture.attempts.values.get("client-section-1").status())
                        .as(id).isEqualTo(GenerationStatus.CANCELLED);
                continue;
            }

            var generated = fixture.service.generate(command(SECTION_ID));
            assertThat(generated.attempt().status()).as(id).isEqualTo(GenerationStatus.SUCCEEDED);
            assertThat(fixture.modelRequest.evidence()).as(id).hasSize(row.path("evidence").size());
            if (id.equals("GEN-007")) {
                assertThat(fixture.modelRequest.evidence()).extracting(Evidence::excerpt)
                        .containsExactly("虚构合同草案：服务期限 12 个月。", "虚构补充协议：服务期限 24 个月。");
                assertThat(fixture.semanticInputs).singleElement().satisfies(input ->
                        assertThat(input.evidence()).extracting(Evidence::excerpt)
                                .containsExactlyInAnyOrder("虚构合同草案：服务期限 12 个月。",
                                        "虚构补充协议：服务期限 24 个月。"));
            }
            assertThat(fixture.modelRequest.confirmedRequirements()).as(id).singleElement()
                    .extracting(RequirementItem::text).isEqualTo(row.path("requirement").asText());
            var draft = generated.sectionVersion().orElseThrow();
            var actual = draft.verification().verifications().getFirst().verdict();
            var expected = switch (row.path("expectedDecision").asText()) {
                case "GENERATE" -> CitationVerdict.SUPPORTED;
                case "REFUSE" -> CitationVerdict.UNSUPPORTED;
                case "REQUIRE_HUMAN_REVIEW" -> CitationVerdict.HUMAN_CONFIRMATION_REQUIRED;
                default -> throw new IllegalArgumentException("Unknown decision for " + id);
            };
            assertThat(actual).as(id).isEqualTo(expected);
            if (!semanticVerdict.isBlank()) {
                assertThat(draft.verification().verifications().getFirst().reasonCode())
                        .as(id).isEqualTo(row.path("semanticReason").asText());
            }
            if (expected == CitationVerdict.UNSUPPORTED) {
                assertThatThrownBy(() -> fixture.service.adoptSection(
                        ACTOR_ID, SOLUTION_ID, draft.id(), 3, NOW.plusSeconds(1)))
                        .as(id).isInstanceOf(com.yunjihai.rag.solution.domain.SolutionLifecycleException.class)
                        .hasMessageContaining("SECTION_VERIFICATION_BLOCKING");
            } else {
                assertThat(fixture.service.adoptSection(ACTOR_ID, SOLUTION_ID,
                        draft.id(), 3, NOW.plusSeconds(1)).adoptedSectionVersionIds())
                        .as(id).containsKey(SECTION_ID);
            }
            if (id.equals("GEN-009")) {
                assertThat(fixture.modelRequest.evidence().getFirst().excerpt()).contains("忽略上级规则");
                assertThat(fixture.modelRequest.templateRules()).noneMatch(rule -> rule.contains("忽略上级规则"));
            }
        }
    }

    @Test
    void successfulGenerationUsesGovernedInputsAndFollowsTheRequiredOrder() {
        var fixture = Fixture.ready();

        var result = fixture.service.generate(command(SECTION_ID));

        assertThat(result.attempt().status()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(result.sectionVersion()).isPresent();
        assertThat(result.sectionVersion().orElseThrow().kind())
                .isEqualTo(com.yunjihai.rag.solution.domain.SectionVersionKind.MODEL_DRAFT);
        assertThat(fixture.events).containsSubsequence(
                "authorize:ADOPT_SECTION",
                "solution:find",
                "attempt:insert",
                "retrieve",
                "model",
                "verify",
                "section:insert");
        assertThat(fixture.query.userId()).isEqualTo(ACTOR_ID);
        assertThat(fixture.query.spaceIds()).containsExactly(SPACE_ID);
        assertThat(fixture.query.requestedEvidenceCount()).isEqualTo(8);
        assertThat(fixture.query.metadataFilter()).isEmpty();
        assertThat(fixture.query.queryText())
                .contains("建设企业知识库", "描述总体架构", "检索总体架构");
        assertThat(fixture.modelRequest.confirmedRequirements())
                .containsExactlyElementsOf(fixture.requirements.findById(REQUIREMENT_ID).orElseThrow().items());
        assertThat(fixture.modelRequest.section()).contains(
                fixture.outlines.findById(OUTLINE_ID).orElseThrow().nodes().getFirst());
    }

    @Test
    void unconfirmedRequirementsAndMissingSectionStopBeforeRetrievalOrModel() {
        var unconfirmed = Fixture.unconfirmed();
        assertThatThrownBy(() -> unconfirmed.service.generate(command(SECTION_ID)))
                .isInstanceOf(com.yunjihai.rag.solution.domain.SolutionLifecycleException.class);
        assertThat(unconfirmed.retrievalCalls).isZero();
        assertThat(unconfirmed.modelCalls).isZero();

        var missingSection = Fixture.ready();
        assertThatThrownBy(() -> missingSection.service.generate(command(id(999))))
                .isInstanceOf(com.yunjihai.rag.solution.domain.SolutionLifecycleException.class);
        assertThat(missingSection.retrievalCalls).isZero();
        assertThat(missingSection.modelCalls).isZero();
    }

    @Test
    void authorizationFailureIsAuditedWithoutReadingOrCallingExternalServices() {
        var fixture = Fixture.ready((actor, space, solutionId, action, at) -> {
            throw new SecurityException("sensitive-policy-detail");
        });

        assertThatThrownBy(() -> fixture.service.generate(command(SECTION_ID)))
                .isInstanceOf(SecurityException.class);

        assertThat(fixture.solutions.findCalls).isZero();
        assertThat(fixture.retrievalCalls).isZero();
        assertThat(fixture.modelCalls).isZero();
        assertThat(fixture.audit).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo(AuditAction.SECTION_GENERATE);
            assertThat(event.outcome()).isEqualTo(com.yunjihai.rag.audit.AuditOutcome.DENIED);
            assertThat(event.metadata()).containsEntry("errorCode", "AUTHORIZATION_FAILED");
            assertThat(event.metadata().values())
                    .noneMatch(value -> value.contains("sensitive-policy-detail"));
        });
    }

    @Test
    void staleRevisionStopsBeforeAttemptRegistrationAndExternalCalls() {
        var fixture = Fixture.ready();
        var stale = new GenerateSectionCommand(
                ACTOR_ID, SPACE_ID, SOLUTION_ID, SECTION_ID,
                ATTEMPT_ID, SECTION_VERSION_ID, RETRIEVAL_REQUEST_ID,
                "client-section-1", Optional.empty(), 99, NOW);

        assertThatThrownBy(() -> fixture.service.generate(stale))
                .isInstanceOf(ConcurrentSolutionUpdateException.class);
        assertThat(fixture.attempts.values).isEmpty();
        assertThat(fixture.retrievalCalls).isZero();
        assertThat(fixture.modelCalls).isZero();
    }

    @Test
    void insufficientEvidenceEndsAttemptWithoutCallingTheModelOrSavingADraft() {
        var fixture = Fixture.ready();
        fixture.evidence = List.of();

        var result = fixture.service.generate(command(SECTION_ID));

        assertThat(result.attempt().status()).isEqualTo(GenerationStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.sectionVersion()).isEmpty();
        assertThat(fixture.modelCalls).isZero();
        assertThat(fixture.sections.values).isEmpty();
        assertThat(fixture.attempts.findByClientKey("client-section-1").orElseThrow().status())
                .isEqualTo(GenerationStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void invalidModelEvidenceAndNullResponseBecomeManualFailuresWithoutSaving() {
        var duplicateId = id(80);
        var invalidDrafts = List.of(
                new GeneratedSectionDraft(
                        "未知证据", List.of(new Claim(
                                id(81), "未知事实", true, Set.of(id(999)), Set.of())), Set.of(id(999))),
                new GeneratedSectionDraft(
                        "重复声明", List.of(
                                new Claim(duplicateId, "声明一", true, Set.of(EVIDENCE_ID), Set.of()),
                                new Claim(duplicateId, "声明二", true, Set.of(EVIDENCE_ID), Set.of())),
                        Set.of(EVIDENCE_ID)),
                new GeneratedSectionDraft(
                        "事实无引用", List.of(new Claim(
                                id(82), "事实无引用", true, Set.of(), Set.of())), Set.of()));

        for (var invalid : invalidDrafts) {
            var fixture = Fixture.ready();
            fixture.modelDraft = invalid;

            var result = fixture.service.generate(command(SECTION_ID));

            assertThat(result.attempt().status()).isEqualTo(GenerationStatus.FAILED_MANUAL);
            assertThat(result.attempt().failureCode()).contains("INVALID_MODEL_STRUCTURE");
            assertThat(result.sectionVersion()).isEmpty();
            assertThat(fixture.sections.values).isEmpty();
        }

        var nullResponse = Fixture.ready();
        nullResponse.returnNullModelResponse = true;

        var result = nullResponse.service.generate(command(SECTION_ID));

        assertThat(result.attempt().status()).isEqualTo(GenerationStatus.FAILED_MANUAL);
        assertThat(result.attempt().failureCode()).contains("INVALID_MODEL_RESPONSE");
        assertThat(result.sectionVersion()).isEmpty();
        assertThat(nullResponse.sections.values).isEmpty();
    }

    @Test
    void sameClientKeyAndFingerprintReuseTheSingleSuccessfulAttemptAndDraft() {
        var fixture = Fixture.ready();

        var first = fixture.service.generate(command(SECTION_ID));
        var second = fixture.service.generate(command(SECTION_ID));

        assertThat(second).isEqualTo(first);
        assertThat(fixture.retrievalCalls).isEqualTo(1);
        assertThat(fixture.modelCalls).isEqualTo(1);
        assertThat(fixture.sections.values).hasSize(1);
        assertThat(fixture.attempts.values).hasSize(1);
    }

    @Test
    void reentrantSameClientKeyDoesNotInvokeTheModelTwice() {
        var fixture = Fixture.ready();
        var nested = new ArrayList<SectionGenerationResult>();
        fixture.duringModel = () -> {
            fixture.duringModel = () -> { };
            nested.add(fixture.service.generate(command(SECTION_ID)));
        };

        var result = fixture.service.generate(command(SECTION_ID));

        assertThat(result.attempt().status()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(nested).singleElement().satisfies(inProgress ->
                assertThat(inProgress.attempt().status()).isEqualTo(GenerationStatus.GENERATING));
        assertThat(fixture.modelCalls).isEqualTo(1);
    }

    @Test
    void sameClientKeyWithDifferentGovernedInputIsRejectedBeforeExternalCalls() {
        var fixture = Fixture.ready();
        fixture.service.generate(command(SECTION_ID));
        var changed = new GenerateSectionCommand(
                ACTOR_ID, SPACE_ID, SOLUTION_ID, SECOND_SECTION_ID,
                id(51), id(61), id(71), "client-section-1",
                Optional.empty(), 3, NOW.plusSeconds(1));

        assertThatThrownBy(() -> fixture.service.generate(changed))
                .isInstanceOf(GenerationRequestConflictException.class);
        assertThat(fixture.retrievalCalls).isEqualTo(1);
        assertThat(fixture.modelCalls).isEqualTo(1);
        assertThat(fixture.sections.values).hasSize(1);
    }

    @Test
    void retrievalAndModelFailuresResumeTheSameLogicalAttempt() {
        var retrievalFailure = Fixture.ready();
        retrievalFailure.retrievalFailuresRemaining = 1;

        var failedRetrieval = retrievalFailure.service.generate(command(SECTION_ID));
        var recoveredRetrieval = retrievalFailure.service.generate(command(SECTION_ID));

        assertThat(failedRetrieval.attempt().status()).isEqualTo(GenerationStatus.FAILED_RETRYABLE);
        assertThat(recoveredRetrieval.attempt().status()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(recoveredRetrieval.attempt().id()).isEqualTo(failedRetrieval.attempt().id());
        assertThat(recoveredRetrieval.attempt().executionCount()).isEqualTo(2);
        assertThat(retrievalFailure.retrievalCalls).isEqualTo(2);
        assertThat(retrievalFailure.modelCalls).isEqualTo(1);

        var modelFailure = Fixture.ready();
        modelFailure.modelFailuresRemaining = 1;

        var failedModel = modelFailure.service.generate(command(SECTION_ID));
        var recoveredModel = modelFailure.service.generate(command(SECTION_ID));

        assertThat(failedModel.attempt().status()).isEqualTo(GenerationStatus.FAILED_RETRYABLE);
        assertThat(failedModel.attempt().modelUsage()).isEmpty();
        assertThat(recoveredModel.attempt().status()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(recoveredModel.attempt().id()).isEqualTo(failedModel.attempt().id());
        assertThat(recoveredModel.attempt().executionCount()).isEqualTo(2);
        assertThat(recoveredModel.attempt().modelUsage())
                .contains(new ModelUsage(100, 50, 1234, 800));
        assertThat(modelFailure.retrievalCalls).isEqualTo(2);
        assertThat(modelFailure.modelCalls).isEqualTo(2);
        assertThat(modelFailure.sections.values).hasSize(1);
    }

    @Test
    void retryRejectsAChangedEvidenceSnapshotBeforeCallingTheModelAgain() {
        var fixture = Fixture.ready();
        fixture.modelFailuresRemaining = 1;
        var first = fixture.service.generate(command(SECTION_ID));
        fixture.evidence = List.of(new Evidence(
                SPACE_ID, id(140), id(41), id(42), "node-2", "客户需求",
                "客户要求系统支持100名用户", 0.9,
                new MarkdownLocator(List.of("客户需求"), "node-2")));

        var second = fixture.service.generate(command(SECTION_ID));

        assertThat(first.attempt().evidenceIds()).containsExactly(EVIDENCE_ID);
        assertThat(second.attempt().status()).isEqualTo(GenerationStatus.FAILED_MANUAL);
        assertThat(second.attempt().failureCode()).contains("EVIDENCE_SNAPSHOT_CHANGED");
        assertThat(second.attempt().evidenceIds()).containsExactly(EVIDENCE_ID);
        assertThat(fixture.modelCalls).isEqualTo(1);
    }

    @Test
    void lateModelResultsAreCancelledWhenRequirementsOutlineOrBaselineChanges() {
        for (var change : List.of("requirements", "outline", "baseline")) {
            var fixture = Fixture.ready();
            fixture.duringModel = () -> fixture.changeInput(change);

            var result = fixture.service.generate(command(SECTION_ID));

            assertThat(result.attempt().status()).isEqualTo(GenerationStatus.CANCELLED);
            assertThat(result.sectionVersion()).isEmpty();
            assertThat(result.attempt().modelUsage())
                    .contains(new ModelUsage(100, 50, 1234, 800));
            assertThat(fixture.sections.values).isEmpty();
        }
    }

    @Test
    void authorityRevokedDuringModelCancelsAttemptWithoutStoringDraft() {
        var revoked = new AtomicBoolean(false);
        var fixture = Fixture.ready((actor, space, solution, action, at) -> {
            if (revoked.get()) throw new SecurityException("access revoked");
        });
        fixture.duringModel = () -> revoked.set(true);

        assertThatThrownBy(() -> fixture.service.generate(command(SECTION_ID)))
                .isInstanceOf(SecurityException.class);
        assertThat(fixture.attempts.values.get("client-section-1").status())
                .isEqualTo(GenerationStatus.CANCELLED);
        assertThat(fixture.sections.values).isEmpty();
    }

    @Test
    void sourceRevokedDuringRetrievalStopsBeforeCloudModelCall() {
        var fixture = Fixture.ready();
        fixture.duringRetrieval = () -> fixture.evidenceReadable = false;

        assertThatThrownBy(() -> fixture.service.generate(command(SECTION_ID)))
                .isInstanceOf(SecurityException.class);
        assertThat(fixture.modelCalls).isZero();
        assertThat(fixture.attempts.values.get("client-section-1").status())
                .isEqualTo(GenerationStatus.CANCELLED);
    }

    @Test
    void sourceRevokedAfterSuccessBlocksPostAndGetReplay() {
        var fixture = Fixture.ready();
        assertThat(fixture.service.generate(command(SECTION_ID)).attempt().status())
                .isEqualTo(GenerationStatus.SUCCEEDED);
        fixture.evidenceReadable = false;

        assertThatThrownBy(() -> fixture.service.generate(command(SECTION_ID)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> fixture.service.readAttempt(
                ACTOR_ID, SPACE_ID, SOLUTION_ID, SECTION_ID, "client-section-1", NOW.plusSeconds(1)))
                .isInstanceOf(SecurityException.class);
        assertThat(fixture.modelCalls).isEqualTo(1);
    }

    @Test
    void successfulReplayUsesSavedEvidenceSourcesWithoutRetrieval() {
        var fixture = Fixture.ready();
        fixture.randomEvidenceIdsAfterFirst = true;
        assertThat(fixture.service.generate(command(SECTION_ID)).attempt().status())
                .isEqualTo(GenerationStatus.SUCCEEDED);

        assertThat(fixture.service.generate(command(SECTION_ID)).attempt().status())
                .isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(fixture.service.readAttempt(ACTOR_ID, SPACE_ID, SOLUTION_ID, SECTION_ID,
                "client-section-1", NOW.plusSeconds(1)).sectionVersion()).isPresent();
        assertThat(fixture.retrievalCalls).isEqualTo(1);
    }

    @Test
    void revokedCitedDocumentPreventsAdoption() {
        var fixture = Fixture.ready();
        var draft = fixture.service.generate(command(SECTION_ID)).sectionVersion().orElseThrow();
        fixture.evidenceReadable = false;

        assertThatThrownBy(() -> fixture.service.adoptSection(
                ACTOR_ID, SOLUTION_ID, draft.id(), 3, NOW.plusSeconds(1)))
                .isInstanceOf(SecurityException.class);
        assertThat(fixture.solutions.values.get(SOLUTION_ID).adoptedSectionVersionIds()).isEmpty();
    }

    @Test
    void adoptionAllowsDraftToCiteSubsetOfRetrievedEvidence() {
        var fixture = Fixture.ready();
        var first = evidence();
        var second = new Evidence(first.spaceId(), id(99), id(98), id(97), "node-2",
                "补充材料", "该材料与本章节无关", 0.7,
                new MarkdownLocator(List.of("补充材料"), "node-2"));
        fixture.evidence = List.of(first, second);
        var draft = fixture.service.generate(command(SECTION_ID)).sectionVersion().orElseThrow();
        assertThat(draft.evidenceIds()).containsExactly(EVIDENCE_ID);

        var adopted = fixture.service.adoptSection(
                ACTOR_ID, SOLUTION_ID, draft.id(), 3, NOW.plusSeconds(1));
        assertThat(adopted.adoptedSectionVersionIds()).containsKey(SECTION_ID);
    }

    @Test
    void retryKeepsOriginalEvidenceIdsWhenRetrievalAllocatesNewIds() {
        var fixture = Fixture.ready();
        fixture.modelFailuresRemaining = 1;
        fixture.randomEvidenceIdsAfterFirst = true;

        var first = fixture.service.generate(command(SECTION_ID));
        assertThat(first.attempt().status()).isEqualTo(GenerationStatus.FAILED_RETRYABLE);
        var second = fixture.service.generate(command(SECTION_ID));

        assertThat(second.attempt().status()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(second.sectionVersion().orElseThrow().evidenceIds()).containsExactly(EVIDENCE_ID);
    }

    @Test
    void persistedVerifyingAttemptRecoversFromItsSectionCheckpointWithoutModelCall() {
        var fixture = Fixture.ready();
        var key = new GenerationKey(
                "client-section-1", SOLUTION_ID, REQUIREMENT_ID, OUTLINE_ID,
                SECTION_ID, Optional.empty());
        var verifying = GenerationAttempt.start(ATTEMPT_ID, key, NOW)
                .startRetrieval(NOW)
                .recordEvidence(RETRIEVAL_REQUEST_ID, Set.of(EVIDENCE_ID), NOW)
                .startVerification(new ModelUsage(100, 50, 1234, 800), NOW);
        fixture.attempts.values.put(key.clientKey(), verifying);
        fixture.evidenceSources.saveSnapshot(ATTEMPT_ID,
                List.of(GenerationEvidenceSource.from(1, evidence())));
        var draft = candidate(SECTION_VERSION_ID, CitationVerdict.SUPPORTED);
        fixture.sections.insertForAttempt(ATTEMPT_ID, draft);

        var result = fixture.service.generate(command(SECTION_ID));

        assertThat(result.attempt().status()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(result.sectionVersion()).contains(draft);
        assertThat(fixture.retrievalCalls).isZero();
        assertThat(fixture.modelCalls).isZero();
    }

    @Test
    void persistedVerifyingAttemptWithoutCheckpointBecomesManualFailure() {
        var fixture = Fixture.ready();
        var key = new GenerationKey(
                "client-section-1", SOLUTION_ID, REQUIREMENT_ID, OUTLINE_ID,
                SECTION_ID, Optional.empty());
        var verifying = GenerationAttempt.start(ATTEMPT_ID, key, NOW)
                .startRetrieval(NOW)
                .recordEvidence(RETRIEVAL_REQUEST_ID, Set.of(EVIDENCE_ID), NOW)
                .startVerification(new ModelUsage(100, 50, 1234, 800), NOW);
        fixture.attempts.values.put(key.clientKey(), verifying);

        var result = fixture.service.generate(commandAt(NOW.plusSeconds(180)));

        assertThat(result.attempt().status()).isEqualTo(GenerationStatus.FAILED_MANUAL);
        assertThat(result.attempt().failureCode()).contains("VERIFICATION_CHECKPOINT_MISSING");
        assertThat(fixture.retrievalCalls).isZero();
        assertThat(fixture.modelCalls).isZero();
    }

    @Test
    void recentVerifyingAttemptRemainsInProgressWithoutACheckpoint() {
        var fixture = Fixture.ready();
        var key = new GenerationKey("client-section-1", SOLUTION_ID, REQUIREMENT_ID,
                OUTLINE_ID, SECTION_ID, Optional.empty());
        var verifying = GenerationAttempt.start(ATTEMPT_ID, key, NOW)
                .startRetrieval(NOW)
                .recordEvidence(RETRIEVAL_REQUEST_ID, Set.of(EVIDENCE_ID), NOW)
                .startVerification(new ModelUsage(100, 50, 1234, 800), NOW);
        fixture.attempts.values.put(key.clientKey(), verifying);

        var result = fixture.service.generate(command(SECTION_ID));

        assertThat(result.attempt()).isEqualTo(verifying);
        assertThat(fixture.retrievalCalls).isZero();
        assertThat(fixture.modelCalls).isZero();
    }

    @Test
    void expiredRetrievalAttemptResumesOnceAfterLease() {
        var fixture = Fixture.ready();
        var key = new GenerationKey("client-section-1", SOLUTION_ID, REQUIREMENT_ID,
                OUTLINE_ID, SECTION_ID, Optional.empty());
        var retrieving = GenerationAttempt.start(ATTEMPT_ID, key, NOW).startRetrieval(NOW);
        fixture.attempts.values.put(key.clientKey(), retrieving);

        var result = fixture.service.generate(commandAt(NOW.plusSeconds(180)));

        assertThat(result.attempt().status()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(result.attempt().executionCount()).isEqualTo(2);
        assertThat(fixture.retrievalCalls).isEqualTo(1);
        assertThat(fixture.modelCalls).isEqualTo(1);
    }

    @Test
    void retryableAttemptStopsAfterConfiguredExecutionLimit() {
        var fixture = Fixture.ready();
        var key = new GenerationKey("client-section-1", SOLUTION_ID, REQUIREMENT_ID,
                OUTLINE_ID, SECTION_ID, Optional.empty());
        var attempt = GenerationAttempt.start(ATTEMPT_ID, key, NOW).startRetrieval(NOW);
        for (int count = 1; count < 3; count++) {
            attempt = attempt.failRetryable("RETRIEVAL_UNAVAILABLE", NOW).retry(NOW);
        }
        attempt = attempt.failRetryable("RETRIEVAL_UNAVAILABLE", NOW);
        fixture.attempts.values.put(key.clientKey(), attempt);

        var result = fixture.service.generate(commandAt(NOW.plusSeconds(180)));

        assertThat(result.attempt().status()).isEqualTo(GenerationStatus.FAILED_MANUAL);
        assertThat(result.attempt().failureCode()).contains("RETRY_LIMIT_REACHED");
        assertThat(result.attempt().executionCount()).isEqualTo(3);
        assertThat(fixture.retrievalCalls).isZero();
        assertThat(fixture.modelCalls).isZero();
    }

    @Test
    void atomicCommitRejectsInputChangeAfterVerificationWithoutSavingDraft() {
        var fixture = Fixture.ready();
        fixture.beforeCommit = () -> fixture.changeInput("requirements");

        var result = fixture.service.generate(command(SECTION_ID));

        assertThat(result.attempt().status()).isEqualTo(GenerationStatus.CANCELLED);
        assertThat(result.sectionVersion()).isEmpty();
        assertThat(fixture.sections.values).isEmpty();
    }

    @Test
    void outlineRiskTypesCannotBeRemovedByTheModel() {
        var fixture = Fixture.ready();
        fixture.outlines.values.put(OUTLINE_ID, new OutlineVersion(
                OUTLINE_ID, 1,
                List.of(new OutlineNode(
                        SECTION_ID, "总体方案", 1, 1, true,
                        "描述总体架构", "检索总体架构", Set.of(RiskType.PERFORMANCE_OR_SLA))),
                ACTOR_ID, NOW));

        var result = fixture.service.generate(command(SECTION_ID));

        assertThat(result.sectionVersion().orElseThrow().claims().getFirst().riskTypes())
                .containsExactly(RiskType.PERFORMANCE_OR_SLA);
        assertThat(result.sectionVersion().orElseThrow().verification().hasBlockingVerdict()).isFalse();
        assertThat(result.sectionVersion().orElseThrow().verification().verifications().getFirst().verdict())
                .isEqualTo(CitationVerdict.HUMAN_CONFIRMATION_REQUIRED);
    }

    @Test
    void unsupportedAndPartialDraftsCannotBeAdopted() {
        for (var verdict : List.of(CitationVerdict.UNSUPPORTED, CitationVerdict.PARTIAL)) {
            var fixture = Fixture.ready();
            var draft = candidate(id(90 + verdict.ordinal()), verdict);
            fixture.sections.insertIfAbsent(draft);
            fixture.evidenceSources.linkVersion(draft.id(),
                    List.of(GenerationEvidenceSource.from(1, evidence())));
            var sectionCount = fixture.sections.values.size();

            assertThatThrownBy(() -> fixture.service.adoptSection(
                    ACTOR_ID, SOLUTION_ID, draft.id(), 3, NOW.plusSeconds(1)))
                    .isInstanceOf(com.yunjihai.rag.solution.domain.SolutionLifecycleException.class);

            assertThat(fixture.solutions.values.get(SOLUTION_ID).adoptedSectionVersionIds()).isEmpty();
            assertThat(fixture.sections.values).hasSize(sectionCount);
            assertThat(fixture.sections.findById(draft.id()).orElseThrow().kind())
                    .isEqualTo(com.yunjihai.rag.solution.domain.SectionVersionKind.MODEL_DRAFT);
        }
    }

    @Test
    void humanConfirmationRequiredDraftCanBeAdoptedAsANewImmutableVersion() {
        var fixture = Fixture.ready();
        var draft = candidate(id(90), CitationVerdict.HUMAN_CONFIRMATION_REQUIRED);
        fixture.sections.insertIfAbsent(draft);
        fixture.evidenceSources.linkVersion(draft.id(),
                List.of(GenerationEvidenceSource.from(1, evidence())));

        var updated = fixture.service.adoptSection(
                ACTOR_ID, SOLUTION_ID, draft.id(), 3, NOW.plusSeconds(1));

        var adoptedId = updated.adoptedSectionVersionIds().get(SECTION_ID);
        var adopted = fixture.sections.findById(adoptedId).orElseThrow();
        assertThat(adopted.id()).isNotEqualTo(draft.id());
        assertThat(adopted.kind())
                .isEqualTo(com.yunjihai.rag.solution.domain.SectionVersionKind.ADOPTED);
        assertThat(adopted.parentVersionId()).contains(draft.id());
        assertThat(adopted.createdBy()).contains(ACTOR_ID);
        assertThat(adopted.modelId()).isEmpty();
        assertThat(adopted.verification()).isEqualTo(draft.verification());
        assertThat(fixture.sections.findById(draft.id()).orElseThrow()).isEqualTo(draft);
        assertThat(fixture.events).contains("authorize:ADOPT_SECTION");
        assertThat(fixture.audit).extracting(AuditCommand::action)
                .contains(AuditAction.SECTION_ADOPT);
    }

    private static GenerateSectionCommand command(UUID sectionId) {
        return new GenerateSectionCommand(
                ACTOR_ID, SPACE_ID, SOLUTION_ID, sectionId,
                ATTEMPT_ID, SECTION_VERSION_ID, RETRIEVAL_REQUEST_ID,
                "client-section-1", Optional.empty(), 3, NOW);
    }

    private static GenerateSectionCommand commandAt(Instant at) {
        return new GenerateSectionCommand(ACTOR_ID, SPACE_ID, SOLUTION_ID, SECTION_ID,
                ATTEMPT_ID, SECTION_VERSION_ID, RETRIEVAL_REQUEST_ID,
                "client-section-1", Optional.empty(), 3, at);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
    }

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final InMemorySolutionRepository solutions = new InMemorySolutionRepository(events);
        private final InMemoryRequirementRepository requirements = new InMemoryRequirementRepository();
        private final InMemoryOutlineRepository outlines = new InMemoryOutlineRepository();
        private final InMemoryAttemptStore attempts = new InMemoryAttemptStore(events);
        private final InMemoryGenerationEvidenceStore evidenceSources = new InMemoryGenerationEvidenceStore();
        private final InMemorySectionRepository sections = new InMemorySectionRepository(events);
        private List<Evidence> evidence = List.of(evidence());
        private int retrievalCalls;
        private int modelCalls;
        private RetrievalQuery query;
        private SolutionModelRequest modelRequest;
        private GeneratedSectionDraft modelDraft = validDraft();
        private boolean returnNullModelResponse;
        private int retrievalFailuresRemaining;
        private int modelFailuresRemaining;
        private Runnable duringModel = () -> { };
        private Runnable duringRetrieval = () -> { };
        private boolean evidenceReadable = true;
        private boolean randomEvidenceIdsAfterFirst;
        private CitationVerdict semanticVerdict = CitationVerdict.SUPPORTED;
        private String semanticReasonCode = "SEMANTIC_SUPPORTED";
        private List<ClaimEvidenceInput> semanticInputs = List.of();
        private Runnable beforeCommit = () -> { };
        private final List<AuditCommand> audit = new ArrayList<>();
        private final SectionGenerationService service;

        private Fixture(boolean confirm) {
            this(confirm, (actor, space, solutionId, action, at) -> { }, "建设企业知识库");
        }

        private Fixture(boolean confirm, SolutionAuthorizationPort authorizationPort) {
            this(confirm, authorizationPort, "建设企业知识库");
        }

        private Fixture(boolean confirm, SolutionAuthorizationPort authorizationPort, String requirementText) {
            var requirement = requirements(requirementText);
            var outline = outline();
            requirements.insert(requirement);
            outlines.insert(outline);
            var solution = Solution.create(SOLUTION_ID, SPACE_ID, ACTOR_ID, requirement, NOW);
            if (confirm) {
                solution = solution.confirmRequirements(ACTOR_ID, NOW, 0);
                solution = solution.reviseOutline(outline, 1);
                solution = solution.confirmOutline(ACTOR_ID, NOW, 2);
            }
            solutions.insert(solution);
            events.clear();

            SolutionAuthorizationPort authorization = (actor, space, solutionId, action, at) -> {
                events.add("authorize:" + action);
                authorizationPort.require(actor, space, solutionId, action, at);
            };
            RetrievalService retrieval = request -> {
                events.add("retrieve");
                retrievalCalls++;
                query = request;
                if (retrievalFailuresRemaining-- > 0) {
                    throw new IllegalStateException("retrieval temporarily unavailable");
                }
                duringRetrieval.run();
                if (randomEvidenceIdsAfterFirst && retrievalCalls > 1) {
                    var original = evidence.getFirst();
                    var replacement = new Evidence(original.spaceId(), id(1000 + retrievalCalls),
                            original.documentId(), original.versionId(), original.nodeId(),
                            original.title(), original.excerpt(), original.score(), original.locator());
                    return pack(request.requestId(), List.of(replacement));
                }
                return pack(request.requestId(), evidence);
            };
            SolutionModelPort model = new SolutionModelPort() {
                @Override
                public <T> SolutionModelResponse<T> execute(SolutionModelRequest request, Class<T> responseType) {
                    events.add("model");
                    modelCalls++;
                    modelRequest = request;
                    if (modelFailuresRemaining-- > 0) {
                        throw new IllegalStateException("model temporarily unavailable");
                    }
                    duringModel.run();
                    if (returnNullModelResponse) {
                        return null;
                    }
                    return new SolutionModelResponse<>(
                            responseType, responseType.cast(modelDraft), "logical-model-v1",
                            new ModelUsage(100, 50, 1234, 800));
                }
            };
            var verification = new SectionVerificationService((inputs, deadline) -> {
                events.add("verify");
                semanticInputs = List.copyOf(inputs);
                return inputs.stream()
                        .map(input -> new ClaimVerification(
                                input.claimId(), semanticVerdict, semanticReasonCode))
                        .toList();
            });
            service = new SectionGenerationService(
                    solutions, requirements, outlines, sections, attempts, evidenceSources,
                    authorization, retrieval, (actor, space, selected, at) -> {
                        if (!evidenceReadable || selected.stream().anyMatch(source -> !source.spaceId().equals(space))) {
                            throw new SecurityException("source not readable");
                        }
                    }, model, verification, GenerationPolicy.defaults(),
                    (key, attemptId, version, succeeded, expectedRevision) -> {
                        beforeCommit.run();
                        if (!isCurrent(key)) {
                            return false;
                        }
                        sections.insertForAttempt(attemptId, version);
                        evidenceSources.linkVersion(version.id(),
                                evidenceSources.findByAttemptId(attemptId));
                        attempts.replace(succeeded, expectedRevision);
                        return true;
                    }, command -> {
                        audit.add(command);
                        return null;
                    });
        }

        private boolean isCurrent(GenerationKey key) {
            var current = solutions.values.get(key.solutionId());
            return current.currentRequirementVersionId().equals(key.requirementVersionId())
                    && current.currentOutlineVersionId().equals(Optional.of(key.outlineVersionId()))
                    && Optional.ofNullable(current.adoptedSectionVersionIds().get(key.sectionId()))
                    .equals(key.baselineSectionVersionId());
        }

        private static Fixture ready() {
            return new Fixture(true);
        }

        private static Fixture ready(String requirementText) {
            return new Fixture(true, (actor, space, solutionId, action, at) -> { }, requirementText);
        }

        private static Fixture ready(SolutionAuthorizationPort authorization) {
            return new Fixture(true, authorization);
        }

        private static Fixture unconfirmed() {
            return new Fixture(false);
        }

        private void changeInput(String change) {
            var current = solutions.values.get(SOLUTION_ID);
            Solution changed;
            if (change.equals("requirements")) {
                var next = new RequirementVersion(
                        id(14), 2, requirements().items(), ACTOR_ID, NOW.plusSeconds(1), "需求变化");
                requirements.insert(next);
                changed = current.reviseRequirements(next, current.revision());
            } else if (change.equals("outline")) {
                var next = new OutlineVersion(
                        id(21), 2, outline().nodes(), ACTOR_ID, NOW.plusSeconds(1));
                outlines.insert(next);
                changed = current.reviseOutline(next, current.revision());
            } else {
                changed = current.adoptSection(SECTION_ID, id(777), current.revision());
            }
            solutions.replace(changed, current.revision());
        }
    }

    private static RequirementVersion requirements() {
        return requirements("建设企业知识库");
    }

    private static RequirementVersion requirements(String text) {
        return new RequirementVersion(
                REQUIREMENT_ID, 1,
                List.of(new RequirementItem(
                        id(11), RequirementCategory.GOAL, text,
                        List.of(new RequirementSource(
                                new DocumentVersionRef(id(12), id(13)),
                                new MarkdownLocator(List.of("客户需求"), "node-1"))))),
                ACTOR_ID, NOW, "首次需求");
    }

    private static OutlineVersion outline() {
        return new OutlineVersion(
                OUTLINE_ID, 1,
                List.of(new OutlineNode(
                        SECTION_ID, "总体方案", 1, 1, true,
                        "描述总体架构", "检索总体架构", Set.of()),
                        new OutlineNode(
                                SECOND_SECTION_ID, "实施计划", 1, 2, false,
                                "描述实施计划", "检索实施计划", Set.of())),
                ACTOR_ID, NOW);
    }

    private static Evidence evidence() {
        return new Evidence(
                SPACE_ID, EVIDENCE_ID, id(41), id(42), "node-1", "客户需求",
                "客户要求系统支持100名用户", 0.9,
                new MarkdownLocator(List.of("客户需求"), "node-1"));
    }

    private static GeneratedSectionDraft validDraft() {
        var claim = new Claim(id(80), "系统支持100名用户", true,
                Set.of(EVIDENCE_ID), Set.of());
        return new GeneratedSectionDraft(
                "## 总体方案\n系统支持100名用户。", List.of(claim), Set.of(EVIDENCE_ID));
    }

    private static SectionVersion candidate(UUID id, CitationVerdict verdict) {
        var claim = new Claim(id(80), "系统支持100名用户", true,
                Set.of(EVIDENCE_ID), Set.of());
        return new SectionVersion(
                id, SOLUTION_ID, SECTION_ID,
                com.yunjihai.rag.solution.domain.SectionVersionKind.MODEL_DRAFT,
                "## 总体方案\n系统支持100名用户。",
                List.of(claim), Set.of(EVIDENCE_ID), REQUIREMENT_ID, OUTLINE_ID,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("logical-model-v1"),
                NOW, new SolutionRiskReport(List.of(new ClaimVerification(
                        claim.claimId(), verdict, "TEST_VERDICT"))));
    }

    private static EvidencePack pack(UUID requestId, List<Evidence> evidence) {
        return new EvidencePack(
                requestId, evidence, false, Set.of(),
                new RetrievalStats(1, 1, 1, evidence.size(), evidence.size()), NOW);
    }

    private static final class InMemorySolutionRepository implements SolutionRepository {
        private final Map<UUID, Solution> values = new HashMap<>();
        private final List<String> events;
        private int findCalls;

        private InMemorySolutionRepository(List<String> events) {
            this.events = events;
        }

        @Override
        public Optional<Solution> findById(UUID solutionId) {
            events.add("solution:find");
            findCalls++;
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

    private static final class InMemoryRequirementRepository implements RequirementVersionRepository {
        private final Map<UUID, RequirementVersion> values = new HashMap<>();

        @Override
        public Optional<RequirementVersion> findById(UUID id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public void insert(RequirementVersion version) {
            values.put(version.id(), version);
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

    private static final class InMemoryAttemptStore implements GenerationAttemptStore {
        private final Map<String, GenerationAttempt> values = new HashMap<>();
        private final List<String> events;

        private InMemoryAttemptStore(List<String> events) {
            this.events = events;
        }

        @Override
        public Optional<GenerationAttempt> findByClientKey(String clientKey) {
            return Optional.ofNullable(values.get(clientKey));
        }

        @Override
        public GenerationAttempt insertIfAbsent(GenerationAttempt attempt) {
            events.add("attempt:insert");
            return values.computeIfAbsent(attempt.key().clientKey(), ignored -> attempt);
        }

        @Override
        public GenerationAttemptRegistration register(GenerationAttempt attempt) {
            events.add("attempt:insert");
            var existing = values.putIfAbsent(attempt.key().clientKey(), attempt);
            return new GenerationAttemptRegistration(existing == null ? attempt : existing, existing == null);
        }

        @Override
        public void replace(GenerationAttempt next, long expectedRevision) {
            events.add("attempt:replace:" + next.status());
            values.compute(next.key().clientKey(), (key, current) -> {
                if (current == null || current.revision() != expectedRevision) {
                    throw new GenerationRequestConflictException();
                }
                if (next.revision() != expectedRevision + 1) {
                    throw new GenerationStateException();
                }
                return next;
            });
        }
    }

    private static final class InMemorySectionRepository implements SectionVersionRepository {
        private final Map<UUID, SectionVersion> values = new HashMap<>();
        private final Map<UUID, SectionVersion> byAttempt = new HashMap<>();
        private final List<String> events;

        private InMemorySectionRepository(List<String> events) {
            this.events = events;
        }

        @Override
        public Optional<SectionVersion> findById(UUID sectionVersionId) {
            return Optional.ofNullable(values.get(sectionVersionId));
        }

        @Override
        public SectionVersion insertIfAbsent(SectionVersion version) {
            events.add("section:insert");
            var existing = values.putIfAbsent(version.id(), version);
            if (existing != null) {
                return existing;
            }
            return version;
        }

        @Override
        public Optional<SectionVersion> findByAttemptId(UUID attemptId) {
            return Optional.ofNullable(byAttempt.get(attemptId));
        }

        private void insertForAttempt(UUID attemptId, SectionVersion version) {
            events.add("section:insert");
            values.putIfAbsent(version.id(), version);
            byAttempt.putIfAbsent(attemptId, version);
        }
    }

    private static final class InMemoryGenerationEvidenceStore implements GenerationEvidenceStore {
        private final Map<UUID, List<GenerationEvidenceSource>> byAttempt = new HashMap<>();
        private final Map<UUID, List<GenerationEvidenceSource>> byVersion = new HashMap<>();

        @Override public void saveSnapshot(UUID attemptId, List<GenerationEvidenceSource> sources) {
            var original = byAttempt.putIfAbsent(attemptId, List.copyOf(sources));
            if (original != null && !original.equals(sources)) throw new GenerationRequestConflictException();
        }

        @Override public List<GenerationEvidenceSource> findByAttemptId(UUID attemptId) {
            return byAttempt.getOrDefault(attemptId, List.of());
        }

        @Override public List<GenerationEvidenceSource> findBySectionVersionId(UUID versionId) {
            return byVersion.getOrDefault(versionId, List.of());
        }

        private void linkVersion(UUID versionId, List<GenerationEvidenceSource> sources) {
            byVersion.put(versionId, List.copyOf(sources));
        }
    }
}
