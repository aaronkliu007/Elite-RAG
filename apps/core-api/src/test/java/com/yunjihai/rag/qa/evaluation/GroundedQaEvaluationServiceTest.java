package com.yunjihai.rag.qa.evaluation;

import com.yunjihai.rag.qa.domain.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroundedQaEvaluationServiceTest {
    private static final Instant NOW=Instant.parse("2026-10-27T07:00:00Z");

    @Test
    void runsEveryCaseThroughQaAndRecordsAllVersionDimensions() {
        UUID actor=UUID.randomUUID(),document=UUID.randomUUID(),evidence=UUID.randomUUID();
        var dataset=dataset(actor,document,false);
        var repository=new MemoryRepository(dataset,new EvaluationRepository.Observation(
                Set.of(new EvaluationCase.EvidenceRef(document,"node-1")),Set.of(evidence),Set.of(),Set.of()));
        var actors=new HashSet<UUID>();
        var service=new GroundedQaEvaluationService(command->{
            actors.add(command.actorId());
            return new QaOutcome.Answered(command.requestId(),"虚构回答",
                    List.of(new CitationClaim(UUID.randomUUID(),"受支持声明",List.of(evidence))),false);
        },repository,new EvaluationGate(),Clock.fixed(NOW,ZoneOffset.UTC));

        var run=service.run(dataset.id(),"code-abc","policy-v2","model-approved-v3");

        assertThat(run.caseResults()).hasSize(50);
        assertThat(run.caseResults()).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(QaStatus.ANSWERED);
            assertThat(result.reviewedClaims()).isZero();
            assertThat(result.generatedClaims()).isEqualTo(1);
        });
        assertThat(actors).containsExactly(actor);
        assertThat(run.datasetVersion()).isEqualTo("golden-v1");
        assertThat(run.codeVersion()).isEqualTo("code-abc");
        assertThat(run.policyVersion()).isEqualTo("policy-v2");
        assertThat(run.promptVersion()).isEqualTo("prompt-v4");
        assertThat(run.sanitizationVersion()).isEqualTo("sanitizer-v5");
        assertThat(run.modelConfigVersion()).isEqualTo("model-approved-v3");
        assertThat(run.assessment().passed()).isFalse();
        assertThat(run.metrics().supportedClaimCount()).isZero();
        assertThat(run.metrics().reviewedClaimCount()).isZero();
        assertThat(run.assessment().failures()).containsExactly(EvaluationGate.Failure.CLAIM_SUPPORT,
                EvaluationGate.Failure.CLAIM_REVIEW_COVERAGE, EvaluationGate.Failure.CORRECT_REFUSAL);
        assertThat(repository.saved).isEqualTo(run);
    }

    @Test
    void measuresReviewCoverageAgainstGeneratedClaimsWithoutHidingMissingAnswers() {
        UUID actor=UUID.randomUUID(),document=UUID.randomUUID(),evidence=UUID.randomUUID(),version=UUID.randomUUID();
        var ref=new EvaluationCase.EvidenceRef(document,"node-1");
        var source=new EvaluationCase.ReviewedEvidence(document,version,"node-1","a".repeat(64),"c".repeat(64),"e".repeat(64));
        var base=dataset(actor,document,false);
        var cases=base.cases().stream().map(item->new EvaluationCase(item.id(),actor,item.question(),
                item.expectedEvidence(),Set.of(),Set.of(),false,item.coverageTags(),Set.of(new EvaluationCase.ReviewedClaim(
                        "受支持声明",Set.of(source),"review-001",EvaluationCase.ReviewVerdict.SUPPORTED)),"f".repeat(64))).toList();
        var dataset=new EvaluationDataset(base.id(),base.version(),base.promptVersion(),base.sanitizationVersion(),cases,true);
        var repository=new MemoryRepository(dataset,new EvaluationRepository.Observation(
                Set.of(ref),Set.of(evidence),Set.of(),Set.of(),Map.of(evidence,source),"f".repeat(64)));
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var service=new GroundedQaEvaluationService(command->count.getAndIncrement()<25
                ?new QaOutcome.Answered(command.requestId(),"受支持声明",
                        List.of(new CitationClaim(UUID.randomUUID(),"受支持声明",List.of(evidence))),false)
                :new QaOutcome.Refused(command.requestId(),RefusalReason.INSUFFICIENT_EVIDENCE,false),
                repository,new EvaluationGate(),Clock.fixed(NOW,ZoneOffset.UTC));
        var result=service.run(dataset.id(),"code","policy","model");
        assertThat(result.metrics().claimCount()).isEqualTo(50);
        assertThat(result.metrics().generatedClaimCount()).isEqualTo(25);
        assertThat(result.metrics().reviewedClaimCount()).isEqualTo(25);
        assertThat(result.metrics().claimReviewCoverage()).isEqualTo(1.0);
        assertThat(result.metrics().claimSupport()).isEqualTo(0.5);
        assertThat(result.assessment().failures()).contains(EvaluationGate.Failure.CLAIM_SUPPORT)
                .doesNotContain(EvaluationGate.Failure.CLAIM_REVIEW_COVERAGE);
    }

    @Test
    void recordsReviewedUnsupportedClaimsWithoutPretendingTheyWereUnreviewed() {
        UUID actor=UUID.randomUUID(),document=UUID.randomUUID(),evidence=UUID.randomUUID(),version=UUID.randomUUID();
        var ref=new EvaluationCase.EvidenceRef(document,"node-1");
        var source=new EvaluationCase.ReviewedEvidence(document,version,"node-1","a".repeat(64),"c".repeat(64),"e".repeat(64));
        var base=dataset(actor,document,false);
        var cases=new ArrayList<EvaluationCase>();
        for(int index=0;index<base.cases().size();index++) {
            var item=base.cases().get(index);
            var verdict=index<45?EvaluationCase.ReviewVerdict.SUPPORTED:EvaluationCase.ReviewVerdict.UNSUPPORTED;
            cases.add(new EvaluationCase(item.id(),item.evaluationActorId(),item.question(),item.expectedEvidence(),
                    item.forbiddenDocumentIds(),item.forbiddenNodeIds(),item.shouldRefuse(),item.coverageTags(),
                    Set.of(new EvaluationCase.ReviewedClaim("受支持声明",Set.of(source),"review-001",verdict)),"f".repeat(64)));
        }
        var dataset=new EvaluationDataset(base.id(),base.version(),base.promptVersion(),base.sanitizationVersion(),cases,true);
        var repository=new MemoryRepository(dataset,new EvaluationRepository.Observation(
                Set.of(ref),Set.of(evidence),Set.of(),Set.of(),Map.of(evidence,source),"f".repeat(64)));
        var service=new GroundedQaEvaluationService(command->new QaOutcome.Answered(command.requestId(),"受支持声明",
                List.of(new CitationClaim(UUID.randomUUID(),"受支持声明",List.of(evidence))),false),
                repository,new EvaluationGate(),Clock.fixed(NOW,ZoneOffset.UTC));

        var result=service.run(dataset.id(),"code","policy","model");
        assertThat(result.metrics().reviewedClaimCount()).isEqualTo(50);
        assertThat(result.metrics().supportedClaimCount()).isEqualTo(45);
        assertThat(result.assessment().failures()).containsExactly(EvaluationGate.Failure.CORRECT_REFUSAL);
    }

    @Test
    void doesNotApproveAClaimWhenOnlyPartOfReviewedSourceCombinationWasCited() {
        UUID actor=UUID.randomUUID(),document=UUID.randomUUID(),evidence=UUID.randomUUID(),version=UUID.randomUUID();
        var first=new EvaluationCase.ReviewedEvidence(document,version,"node-1","a".repeat(64),"c".repeat(64),"e".repeat(64));
        var second=new EvaluationCase.ReviewedEvidence(document,version,"node-2","b".repeat(64),"c".repeat(64),"e".repeat(64));
        var base=dataset(actor,document,false);
        var cases=base.cases().stream().map(item->new EvaluationCase(item.id(),actor,item.question(),
                Set.of(new EvaluationCase.EvidenceRef(document,"node-1"),new EvaluationCase.EvidenceRef(document,"node-2")),
                Set.of(),Set.of(),false,item.coverageTags(),Set.of(new EvaluationCase.ReviewedClaim(
                        "受支持声明",Set.of(first,second),"review-001",EvaluationCase.ReviewVerdict.SUPPORTED)),"f".repeat(64))).toList();
        var dataset=new EvaluationDataset(base.id(),base.version(),base.promptVersion(),base.sanitizationVersion(),cases,true);
        var repository=new MemoryRepository(dataset,new EvaluationRepository.Observation(
                Set.of(new EvaluationCase.EvidenceRef(document,"node-1")),Set.of(evidence),Set.of(),Set.of(),Map.of(evidence,first),"f".repeat(64)));
        var service=new GroundedQaEvaluationService(command->new QaOutcome.Answered(command.requestId(),"受支持声明",
                List.of(new CitationClaim(UUID.randomUUID(),"受支持声明",List.of(evidence))),false),
                repository,new EvaluationGate(),Clock.fixed(NOW,ZoneOffset.UTC));

        assertThat(service.run(dataset.id(),"code","policy","model").metrics().reviewedClaimCount()).isZero();
    }

    @Test
    void countsOnlyExactReviewedClaimsWhoseSourcesMatchTheAnnotation() {
        UUID actor=UUID.randomUUID(),document=UUID.randomUUID(),evidence=UUID.randomUUID(),version=UUID.randomUUID();
        var ref=new EvaluationCase.EvidenceRef(document,"node-1");
        var reviewedRef=new EvaluationCase.ReviewedEvidence(document,version,"node-1","a".repeat(64),"c".repeat(64),"e".repeat(64));
        var base=dataset(actor,document,false);
        var reviewedCases=base.cases().stream().map(item->new EvaluationCase(item.id(),item.evaluationActorId(),
                item.question(),item.expectedEvidence(),item.forbiddenDocumentIds(),item.forbiddenNodeIds(),
                item.shouldRefuse(),item.coverageTags(),Set.of(new EvaluationCase.ReviewedClaim(
                        "受支持声明",Set.of(reviewedRef),"review-001",EvaluationCase.ReviewVerdict.SUPPORTED)),"f".repeat(64))).toList();
        var reviewed=new EvaluationDataset(base.id(),base.version(),base.promptVersion(),base.sanitizationVersion(),reviewedCases,true);
        var repository=new MemoryRepository(reviewed,new EvaluationRepository.Observation(
                Set.of(ref),Set.of(evidence),Set.of(),Set.of(),Map.of(evidence,reviewedRef),"f".repeat(64)));
        var service=new GroundedQaEvaluationService(command->new QaOutcome.Answered(command.requestId(),"受支持声明",
                List.of(new CitationClaim(UUID.randomUUID(),"受支持声明",List.of(evidence))),false),
                repository,new EvaluationGate(),Clock.fixed(NOW,ZoneOffset.UTC));

        var result=service.run(reviewed.id(),"code","policy","model");

        assertThat(result.metrics().reviewedClaimCount()).isEqualTo(50);
        assertThat(result.metrics().supportedClaimCount()).isEqualTo(50);
        assertThat(result.assessment().failures()).containsExactly(EvaluationGate.Failure.CORRECT_REFUSAL);
    }

    @Test
    void wrongOrUnmappedCitationCannotBorrowAnOtherwiseReviewedClaim() {
        UUID actor=UUID.randomUUID(),document=UUID.randomUUID(),other=UUID.randomUUID(),evidence=UUID.randomUUID(),version=UUID.randomUUID();
        var expected=new EvaluationCase.EvidenceRef(document,"node-1");
        var approvedSource=new EvaluationCase.ReviewedEvidence(document,version,"node-1","a".repeat(64),"c".repeat(64),"e".repeat(64));
        var base=dataset(actor,document,false);
        var reviewedCases=base.cases().stream().map(item->new EvaluationCase(item.id(),item.evaluationActorId(),
                item.question(),item.expectedEvidence(),item.forbiddenDocumentIds(),item.forbiddenNodeIds(),
                item.shouldRefuse(),item.coverageTags(),Set.of(new EvaluationCase.ReviewedClaim(
                        "受支持声明",Set.of(approvedSource),"review-001",EvaluationCase.ReviewVerdict.SUPPORTED)),"f".repeat(64))).toList();
        var reviewed=new EvaluationDataset(base.id(),base.version(),base.promptVersion(),base.sanitizationVersion(),reviewedCases,true);
        for(var mapping:List.of(Map.<UUID,EvaluationCase.ReviewedEvidence>of(),
                Map.of(evidence,new EvaluationCase.ReviewedEvidence(other,version,"node-other","a".repeat(64),"c".repeat(64),"e".repeat(64))),
                Map.of(evidence,new EvaluationCase.ReviewedEvidence(document,UUID.randomUUID(),"node-1","a".repeat(64),"c".repeat(64),"e".repeat(64))),
                Map.of(evidence,new EvaluationCase.ReviewedEvidence(document,version,"node-1","b".repeat(64),"c".repeat(64),"e".repeat(64))),
                Map.of(evidence,new EvaluationCase.ReviewedEvidence(document,version,"node-1","a".repeat(64),"d".repeat(64),"e".repeat(64))),
                Map.of(evidence,new EvaluationCase.ReviewedEvidence(document,version,"node-1","a".repeat(64),"c".repeat(64),"0".repeat(64))))) {
            var repository=new MemoryRepository(reviewed,new EvaluationRepository.Observation(
                    Set.of(expected),Set.of(evidence),Set.of(),Set.of(),mapping,"f".repeat(64)));
            var service=new GroundedQaEvaluationService(command->new QaOutcome.Answered(command.requestId(),"受支持声明",
                    List.of(new CitationClaim(UUID.randomUUID(),"受支持声明",List.of(evidence))),false),
                    repository,new EvaluationGate(),Clock.fixed(NOW,ZoneOffset.UTC));
            var result=service.run(reviewed.id(),"code","policy","model");
            assertThat(result.metrics().reviewedClaimCount()).isZero();
            assertThat(result.assessment().failures()).contains(EvaluationGate.Failure.CLAIM_REVIEW_COVERAGE);
        }
        var validRepository=new MemoryRepository(reviewed,new EvaluationRepository.Observation(
                Set.of(expected),Set.of(evidence),Set.of(),Set.of(),Map.of(evidence,approvedSource),"f".repeat(64)));
        var unrelated=new GroundedQaEvaluationService(command->new QaOutcome.Answered(command.requestId(),"未经标注的声明",
                List.of(new CitationClaim(UUID.randomUUID(),"未经标注的声明",List.of(evidence))),false),
                validRepository,new EvaluationGate(),Clock.fixed(NOW,ZoneOffset.UTC));
        var unrelatedResult=unrelated.run(reviewed.id(),"code","policy","model");
        assertThat(unrelatedResult.metrics().validCitationCount()).isEqualTo(50);
        assertThat(unrelatedResult.metrics().reviewedClaimCount()).isZero();
        assertThat(unrelatedResult.assessment().failures()).contains(EvaluationGate.Failure.CLAIM_REVIEW_COVERAGE);
        var changedQuestion=new MemoryRepository(reviewed,new EvaluationRepository.Observation(
                Set.of(expected),Set.of(evidence),Set.of(),Set.of(),Map.of(evidence,approvedSource),"0".repeat(64)));
        var changedQuestionService=new GroundedQaEvaluationService(command->new QaOutcome.Answered(
                command.requestId(),"受支持声明",List.of(new CitationClaim(UUID.randomUUID(),"受支持声明",List.of(evidence))),false),
                changedQuestion,new EvaluationGate(),Clock.fixed(NOW,ZoneOffset.UTC));
        assertThat(changedQuestionService.run(reviewed.id(),"code","policy","model").metrics().reviewedClaimCount()).isZero();
    }

    @Test
    void failsClosedWhenForbiddenScopeAppearsInModelTrace() {
        UUID actor=UUID.randomUUID(),document=UUID.randomUUID(),forbidden=UUID.randomUUID();
        var dataset=dataset(actor,document,true);
        var repository=new MemoryRepository(dataset,new EvaluationRepository.Observation(Set.of(),Set.of(),
                Set.of(new EvaluationCase.EvidenceRef(forbidden,"forbidden-node")),Set.of()));
        var service=new GroundedQaEvaluationService(command->new QaOutcome.Refused(
                command.requestId(),RefusalReason.INSUFFICIENT_EVIDENCE,false),repository,
                new EvaluationGate(),Clock.fixed(NOW,ZoneOffset.UTC));

        var run=service.run(dataset.id(),"code","policy","model");

        assertThat(run.metrics().leakageCount()).isPositive();
        assertThat(run.assessment().failures()).contains(EvaluationGate.Failure.LEAKAGE);
    }

    @Test
    void failedEvaluationReleasesTraceCapacityEvenWhenQaOrObservationThrows() {
        UUID actor=UUID.randomUUID(),document=UUID.randomUUID();
        var dataset=dataset(actor,document,false);
        var traces=new EvaluationTraceRegistry();
        var repository=new MemoryRepository(dataset,new EvaluationRepository.Observation(Set.of(),Set.of(),Set.of(),Set.of()));
        var failedQa=new GroundedQaEvaluationService(command->{ throw new IllegalStateException("qa failed"); },
                repository,new EvaluationGate(),Clock.fixed(NOW,ZoneOffset.UTC),traces);
        for(int i=0;i<101;i++) {
            assertThatThrownBy(()->failedQa.run(dataset.id(),"code","policy","model"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("qa failed");
        }
        EvaluationRepository unavailable=new EvaluationRepository() {
            public Optional<EvaluationDataset> findDataset(UUID id) { return repository.findDataset(id); }
            public Observation observe(UUID id) { throw new IllegalStateException("observation failed"); }
            public void save(EvaluationRun run) { repository.save(run); }
            public Optional<EvaluationRun> findRun(UUID id) { return repository.findRun(id); }
        };
        var failedObservation=new GroundedQaEvaluationService(command->new QaOutcome.Refused(
                command.requestId(),RefusalReason.INSUFFICIENT_EVIDENCE,false),unavailable,
                new EvaluationGate(),Clock.fixed(NOW,ZoneOffset.UTC),traces);
        for(int i=0;i<101;i++) {
            assertThatThrownBy(()->failedObservation.run(dataset.id(),"code","policy","model"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("observation failed");
        }
    }

    private static EvaluationDataset dataset(UUID actor,UUID document,boolean refusal) {
        List<EvaluationCase> cases=new ArrayList<>();
        for(int index=0;index<50;index++) {
            cases.add(new EvaluationCase(UUID.nameUUIDFromBytes(("case-"+index).getBytes()),actor,
                    "虚构评测问题 "+index,
                    refusal?Set.of():Set.of(new EvaluationCase.EvidenceRef(document,"node-1")),
                    refusal?Set.of(document):Set.of(),refusal?Set.of("forbidden-node"):Set.of(),
                    refusal,Set.of(refusal?"REFUSAL":"WORD")));
        }
        return new EvaluationDataset(UUID.randomUUID(),"golden-v1","prompt-v4","sanitizer-v5",cases,true);
    }

    @Test
    void rechecksDatasetContentWhenLoadingRun() {
        var dataset=dataset(UUID.randomUUID(),UUID.randomUUID(),false);
        var repository=new MemoryRepository(dataset,new EvaluationRepository.Observation(Set.of(),Set.of(),Set.of(),Set.of()));
        var service=new GroundedQaEvaluationService(command->new QaOutcome.Refused(command.requestId(),
                RefusalReason.INSUFFICIENT_EVIDENCE,false),repository,new EvaluationGate(),Clock.fixed(NOW,ZoneOffset.UTC));
        var run=service.run(dataset.id(),"code","policy","model");
        assertThat(service.find(run.runId()).orElseThrow().currentAssessment().failures())
                .doesNotContain(EvaluationGate.Failure.DATASET_PROVENANCE);
        repository.dataset=new EvaluationDataset(dataset.id(),dataset.version(),dataset.promptVersion(),
                dataset.sanitizationVersion(),dataset.cases(),false);
        assertThat(service.find(run.runId()).orElseThrow().currentAssessment().failures())
                .contains(EvaluationGate.Failure.DATASET_PROVENANCE);
    }

    private static final class MemoryRepository implements EvaluationRepository {
        private EvaluationDataset dataset;
        private final Observation observation;
        private EvaluationRun saved;
        private MemoryRepository(EvaluationDataset dataset,Observation observation) {
            this.dataset=dataset; this.observation=observation;
        }
        public Optional<EvaluationDataset> findDataset(UUID id) { return dataset.id().equals(id)?Optional.of(dataset):Optional.empty(); }
        public Observation observe(UUID requestId) { return observation; }
        public void save(EvaluationRun run) { saved=run; }
        public Optional<EvaluationRun> findRun(UUID runId) { return Optional.ofNullable(saved).filter(run->run.runId().equals(runId)); }
    }
}
