package com.yunjihai.rag.qa.evaluation;

import java.util.*;

public final class EvaluationGate {
    public enum Failure { LEAKAGE,CITATION_VALIDITY,RECALL_AT_10,CLAIM_SUPPORT,CLAIM_REVIEW_COVERAGE,CORRECT_REFUSAL,DATASET_PROVENANCE }
    public record Assessment(boolean passed,List<Failure> failures) {
        public Assessment { failures=List.copyOf(Objects.requireNonNull(failures)); }
    }

    public Assessment assess(EvaluationMetrics metrics) {
        Objects.requireNonNull(metrics);
        List<Failure> failures=new ArrayList<>();
        if(metrics.leakageCount()>0)failures.add(Failure.LEAKAGE);
        if(metrics.citationValidity()<1d)failures.add(Failure.CITATION_VALIDITY);
        if(metrics.recallAt10()<0.85d)failures.add(Failure.RECALL_AT_10);
        if(metrics.claimSupport()<0.90d)failures.add(Failure.CLAIM_SUPPORT);
        if(metrics.claimReviewCoverage()<1d||metrics.supportedClaimCount()>metrics.reviewedClaimCount())
            failures.add(Failure.CLAIM_REVIEW_COVERAGE);
        if(metrics.correctRefusalRate()<1d)failures.add(Failure.CORRECT_REFUSAL);
        return new Assessment(failures.isEmpty(),failures);
    }
}
