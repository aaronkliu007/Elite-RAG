package com.yunjihai.rag.qa.evaluation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationDatasetFingerprintTest {
    @Test
    void fingerprintChangesWhenReviewedContentOrQuestionChangesWithoutAVersionChange() {
        UUID id=UUID.randomUUID(),actor=UUID.randomUUID(),document=UUID.randomUUID(),version=UUID.randomUUID();
        var source=new EvaluationCase.ReviewedEvidence(document,version,"node","a".repeat(64),"b".repeat(64),"c".repeat(64));
        var label=new EvaluationCase.ReviewedClaim("声明",Set.of(source),"review-1",EvaluationCase.ReviewVerdict.SUPPORTED);
        var first=new EvaluationCase(UUID.randomUUID(),actor,"问题一",Set.of(new EvaluationCase.EvidenceRef(document,"node")),
                Set.of(),Set.of(),false,Set.of("WORD"),Set.of(label),"d".repeat(64));
        var data=new EvaluationDataset(id,"v1","p1","s1",List.of(first),false);
        var alteredQuestion=new EvaluationCase(first.id(),actor,"问题二",first.expectedEvidence(),Set.of(),Set.of(),false,
                first.coverageTags(),first.reviewedClaims(),first.reviewQuestionDigest());
        var changed=new EvaluationDataset(id,"v1","p1","s1",List.of(alteredQuestion),false);
        assertThat(EvaluationDatasetFingerprint.sha256(data)).matches("[0-9a-f]{64}");
        assertThat(EvaluationDatasetFingerprint.sha256(data)).isEqualTo(EvaluationDatasetFingerprint.sha256(data));
        assertThat(EvaluationDatasetFingerprint.sha256(data)).isNotEqualTo(EvaluationDatasetFingerprint.sha256(changed));
    }

    @Test
    void currentAssessmentRejectsMissingOrChangedCatalogEvenWhenHistoricalResultPassed() {
        var metrics=new EvaluationMetrics(1,1,1,1,1,1,1,1,0,1);
        var old=new EvaluationGate.Assessment(true,List.of());
        UUID run=UUID.randomUUID(),dataset=UUID.randomUUID();
        var changed=new EvaluationRepository.EvaluationRun(run,dataset,"v1","c1","p1","p1","s1","m1",
                Instant.EPOCH,Instant.EPOCH,metrics,old,"a".repeat(64),"b".repeat(64));
        assertThat(changed.assessment().passed()).isTrue();
        assertThat(changed.currentAssessment().failures()).contains(EvaluationGate.Failure.DATASET_PROVENANCE);
        var missing=new EvaluationRepository.EvaluationRun(run,dataset,"v1","c1","p1","p1","s1","m1",
                Instant.EPOCH,Instant.EPOCH,metrics,old);
        assertThat(missing.currentAssessment().failures()).contains(EvaluationGate.Failure.DATASET_PROVENANCE);
        var same=new EvaluationRepository.EvaluationRun(run,dataset,"v1","c1","p1","p1","s1","m1",
                Instant.EPOCH,Instant.EPOCH,metrics,old,"a".repeat(64),"a".repeat(64));
        assertThat(same.currentAssessment().passed()).isTrue();
    }
}
