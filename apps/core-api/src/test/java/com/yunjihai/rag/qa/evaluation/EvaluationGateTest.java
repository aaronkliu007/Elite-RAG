package com.yunjihai.rag.qa.evaluation;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationGateTest {
    @Test
    void enforcesSecurityAndQualityThresholdsWithInclusiveBoundaries() {
        var gate=new EvaluationGate();
        var boundary=new EvaluationMetrics(100,85,100,100,100,90,100,90,0);

        assertThat(gate.assess(boundary).passed()).isTrue();
        assertThat(gate.assess(new EvaluationMetrics(100,84,100,100,100,90,100,90,0)).failures())
                .containsExactly(EvaluationGate.Failure.RECALL_AT_10);
        assertThat(gate.assess(new EvaluationMetrics(100,85,100,99,100,90,100,90,0)).failures())
                .containsExactly(EvaluationGate.Failure.CITATION_VALIDITY);
        assertThat(gate.assess(new EvaluationMetrics(100,85,100,100,100,89,100,90,0)).failures())
                .containsExactly(EvaluationGate.Failure.CLAIM_SUPPORT);
        assertThat(gate.assess(new EvaluationMetrics(100,85,100,100,100,90,100,89,0)).failures())
                .containsExactly(EvaluationGate.Failure.CORRECT_REFUSAL);
        assertThat(gate.assess(new EvaluationMetrics(100,100,100,100,100,100,100,100,1)).failures())
                .containsExactly(EvaluationGate.Failure.LEAKAGE);
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
                EvaluationGate.Failure.CLAIM_SUPPORT,EvaluationGate.Failure.CORRECT_REFUSAL);
        assertThat(new EvaluationGate().assess(new EvaluationMetrics(1,1,1,1,1,1,0,0,0)).failures())
                .containsExactly(EvaluationGate.Failure.CORRECT_REFUSAL);
        assertThat(new EvaluationGate().assess(new EvaluationMetrics(0,0,1,1,1,1,1,1,0)).failures())
                .containsExactly(EvaluationGate.Failure.RECALL_AT_10);
        assertThat(new EvaluationGate().assess(new EvaluationMetrics(1,1,0,0,1,1,1,1,0)).failures())
                .containsExactly(EvaluationGate.Failure.CITATION_VALIDITY);
        assertThat(new EvaluationGate().assess(new EvaluationMetrics(1,1,1,1,0,0,1,1,0)).failures())
                .containsExactly(EvaluationGate.Failure.CLAIM_SUPPORT);
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
