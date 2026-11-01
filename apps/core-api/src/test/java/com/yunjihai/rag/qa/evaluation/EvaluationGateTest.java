package com.yunjihai.rag.qa.evaluation;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationGateTest {
    @Test
    void readsLegacyGoldenDatasetAndMetricsAsUnreviewed() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        try(var input=getClass().getResourceAsStream("/contracts/qa/golden-dataset-v1.json")) {
            var dataset=json.readValue(input,EvaluationDataset.class);
            assertThat(dataset.cases()).hasSize(50).allSatisfy(item->assertThat(item.reviewedClaims()).isEmpty());
        }
        var legacy=json.readValue("""
                {"expectedEvidenceCount":1,"retrievedExpectedEvidenceCount":1,"citationCount":1,
                 "validCitationCount":1,"claimCount":1,"supportedClaimCount":1,
                 "refusalCaseCount":1,"correctlyRefusedCount":1,"leakageCount":0}
                """,EvaluationMetrics.class);
        assertThat(legacy.reviewedClaimCount()).isZero();
        assertThat(new EvaluationGate().assess(legacy).failures()).contains(EvaluationGate.Failure.CLAIM_REVIEW_COVERAGE);
        var jackson3=new tools.jackson.databind.ObjectMapper().readValue("""
                {"expectedEvidenceCount":1,"retrievedExpectedEvidenceCount":1,"citationCount":1,
                 "validCitationCount":1,"claimCount":1,"supportedClaimCount":1,
                 "refusalCaseCount":1,"correctlyRefusedCount":1,"leakageCount":0}
                """,EvaluationMetrics.class);
        assertThat(jackson3.reviewedClaimCount()).isZero();
        assertThat(jackson3.generatedClaimCount()).isZero();
    }

    @Test
    void rejectsReviewedClaimsWhoseEvidenceIsNotExpectedOrWhoseReviewReferenceIsMissing() {
        UUID actor=UUID.randomUUID(),document=UUID.randomUUID(),version=UUID.randomUUID(),other=UUID.randomUUID();
        var approved=new EvaluationCase.ReviewedEvidence(other,version,"node","a".repeat(64),"c".repeat(64),"e".repeat(64));
        assertThatThrownBy(()->new EvaluationCase.ReviewedClaim("声明",Set.of(approved)," ",EvaluationCase.ReviewVerdict.SUPPORTED))
                .isInstanceOf(IllegalArgumentException.class);
        var reviewed=new EvaluationCase.ReviewedClaim("声明",Set.of(approved),"review-1",EvaluationCase.ReviewVerdict.SUPPORTED);
        assertThatThrownBy(()->new EvaluationCase(UUID.randomUUID(),actor,"问题",
                Set.of(new EvaluationCase.EvidenceRef(document,"node")),Set.of(),Set.of(),false,
                Set.of("WORD"),Set.of(reviewed),"f".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new EvaluationCase.ReviewedEvidence(document,version,"node","wrong-digest","c".repeat(64),"e".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        var validSource=new EvaluationCase.ReviewedEvidence(document,version,"node","b".repeat(64),"c".repeat(64),"e".repeat(64));
        var positive=new EvaluationCase.ReviewedClaim("声明",Set.of(validSource),"review-1",EvaluationCase.ReviewVerdict.SUPPORTED);
        var negative=new EvaluationCase.ReviewedClaim("声明",Set.of(validSource),"review-2",EvaluationCase.ReviewVerdict.UNSUPPORTED);
        assertThatThrownBy(()->new EvaluationCase(UUID.randomUUID(),actor,"问题",
                Set.of(new EvaluationCase.EvidenceRef(document,"node")),Set.of(),Set.of(),false,
                Set.of("WORD"),Set.of(positive,negative),"f".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTripsVersionedReviewedClaimAnnotations() throws Exception {
        UUID actor=UUID.randomUUID(),document=UUID.randomUUID(),version=UUID.randomUUID();
        var source=new EvaluationCase.ReviewedEvidence(document,version,"node","a".repeat(64),"c".repeat(64),"e".repeat(64));
        var item=new EvaluationCase(UUID.randomUUID(),actor,"问题",Set.of(new EvaluationCase.EvidenceRef(document,"node")),
                Set.of(),Set.of(),false,Set.of("WORD"),Set.of(new EvaluationCase.ReviewedClaim(
                        "人工审核声明",Set.of(source),"review-001",EvaluationCase.ReviewVerdict.UNSUPPORTED)),"f".repeat(64));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(json.readValue(json.writeValueAsString(item),EvaluationCase.class)).isEqualTo(item);
    }

    @Test
    void enforcesSecurityAndQualityThresholdsWithInclusiveBoundaries() {
        var gate=new EvaluationGate();
        var boundary=new EvaluationMetrics(100,85,100,100,100,90,100,100,0,100);

        assertThat(gate.assess(boundary).passed()).isTrue();
        assertThat(gate.assess(new EvaluationMetrics(100,84,100,100,100,90,100,100,0,100)).failures())
                .containsExactly(EvaluationGate.Failure.RECALL_AT_10);
        assertThat(gate.assess(new EvaluationMetrics(100,85,100,99,100,90,100,100,0,100)).failures())
                .containsExactly(EvaluationGate.Failure.CITATION_VALIDITY);
        assertThat(gate.assess(new EvaluationMetrics(100,85,100,100,100,89,100,100,0,100)).failures())
                .containsExactly(EvaluationGate.Failure.CLAIM_SUPPORT);
        assertThat(gate.assess(new EvaluationMetrics(100,85,100,100,100,90,100,99,0,100)).failures())
                .containsExactly(EvaluationGate.Failure.CORRECT_REFUSAL);
        assertThat(gate.assess(new EvaluationMetrics(100,100,100,100,100,100,100,100,1,100)).failures())
                .containsExactly(EvaluationGate.Failure.LEAKAGE);
        assertThat(gate.assess(new EvaluationMetrics(100,100,100,100,100,100,100,100,0,99)).failures())
                .contains(EvaluationGate.Failure.CLAIM_REVIEW_COVERAGE);
    }

    @Test
    void missingMetricSamplesCannotPassTheQualityGate() {
        var metrics=new EvaluationMetrics(0,0,0,0,0,0,0,0,0);
        assertThat(metrics.recallAt10()).isZero();
        assertThat(metrics.citationValidity()).isZero();
        assertThat(metrics.claimSupport()).isZero();
        assertThat(metrics.correctRefusalRate()).isZero();
        assertThat(new EvaluationGate().assess(metrics).failures()).containsExactly(
                EvaluationGate.Failure.CITATION_VALIDITY,EvaluationGate.Failure.RECALL_AT_10,
                EvaluationGate.Failure.CLAIM_SUPPORT,EvaluationGate.Failure.CLAIM_REVIEW_COVERAGE,
                EvaluationGate.Failure.CORRECT_REFUSAL);
        assertThat(new EvaluationGate().assess(new EvaluationMetrics(1,1,1,1,1,1,0,0,0,1)).failures())
                .containsExactly(EvaluationGate.Failure.CORRECT_REFUSAL);
        assertThat(new EvaluationGate().assess(new EvaluationMetrics(0,0,1,1,1,1,1,1,0,1)).failures())
                .containsExactly(EvaluationGate.Failure.RECALL_AT_10);
        assertThat(new EvaluationGate().assess(new EvaluationMetrics(1,1,0,0,1,1,1,1,0,1)).failures())
                .containsExactly(EvaluationGate.Failure.CITATION_VALIDITY);
        assertThat(new EvaluationGate().assess(new EvaluationMetrics(1,1,1,1,0,0,1,1,0)).failures())
                .containsExactly(EvaluationGate.Failure.CLAIM_SUPPORT, EvaluationGate.Failure.CLAIM_REVIEW_COVERAGE);
    }

    @Test
    void datasetCannotActivateBelowFiftyCasesAndCasesRequireEvaluationScope() {
        UUID datasetId=UUID.randomUUID(),actor=UUID.randomUUID(),document=UUID.randomUUID();
        var validCase=new EvaluationCase(UUID.randomUUID(),actor,"虚构制度何时生效？",
                Set.of(new EvaluationCase.EvidenceRef(document,"node-1")),Set.of(),Set.of(),false,
                Set.of("WORD"));
        var cases=java.util.stream.IntStream.range(0,49).mapToObj(index->new EvaluationCase(
                UUID.nameUUIDFromBytes(("small-case-"+index).getBytes()),actor,"虚构制度何时生效？",
                validCase.expectedEvidence(),Set.of(),Set.of(),false,Set.of("WORD"))).toList();
        var dataset=new EvaluationDataset(datasetId,"golden-v1","prompt-v1","sanitizer-v1",cases,false);

        assertThatThrownBy(dataset::activate).isInstanceOf(IllegalStateException.class)
                .hasMessage("EVALUATION_DATASET_REQUIRES_50_CASES");
        assertThatThrownBy(()->new EvaluationCase(UUID.randomUUID(),actor,"问题",Set.of(),
                Set.of(),Set.of(),false,Set.of("PDF")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("evaluation case requires expected evidence, forbidden scope, or refusal");
    }
}
