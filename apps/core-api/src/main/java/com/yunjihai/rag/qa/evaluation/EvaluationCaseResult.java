package com.yunjihai.rag.qa.evaluation;

import com.yunjihai.rag.qa.domain.QaStatus;
import java.util.Objects;
import java.util.UUID;

/** Aggregate case diagnostics only: no question, answer, source text or model payload. */
public record EvaluationCaseResult(UUID caseId, UUID requestId, boolean expectedRefusal,
        QaStatus status, String reason, int expectedEvidence, int retrievedEvidence,
        int generatedClaims, int reviewedClaims, int supportedClaims, int leakageCount, String reviewBinding,
        boolean conflictScreeningCompleted) {
    public EvaluationCaseResult(UUID caseId,UUID requestId,boolean expectedRefusal,QaStatus status,String reason,
                                int expectedEvidence,int retrievedEvidence,int generatedClaims,int reviewedClaims,
                                int supportedClaims,int leakageCount) {
        this(caseId,requestId,expectedRefusal,status,reason,expectedEvidence,retrievedEvidence,generatedClaims,
                reviewedClaims,supportedClaims,leakageCount,null,false);
    }
    public EvaluationCaseResult(UUID caseId,UUID requestId,boolean expectedRefusal,QaStatus status,String reason,
                                int expectedEvidence,int retrievedEvidence,int generatedClaims,int reviewedClaims,
                                int supportedClaims,int leakageCount,String reviewBinding) {
        this(caseId,requestId,expectedRefusal,status,reason,expectedEvidence,retrievedEvidence,generatedClaims,
                reviewedClaims,supportedClaims,leakageCount,reviewBinding,false);
    }
    public EvaluationCaseResult {
        Objects.requireNonNull(caseId); Objects.requireNonNull(requestId); Objects.requireNonNull(status);
        if(status!=QaStatus.ANSWERED && status!=QaStatus.REFUSED && status!=QaStatus.FAILED)
            throw new IllegalArgumentException("case status must be terminal");
        if(reviewBinding!=null && !reviewBinding.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid question review token");
        if(reason!=null && !reason.matches("[A-Z][A-Z0-9_]{1,63}"))
            throw new IllegalArgumentException("invalid outcome reason");
        if(expectedEvidence<0 || retrievedEvidence<0 || retrievedEvidence>expectedEvidence || generatedClaims<0
                || reviewedClaims<0 || reviewedClaims>generatedClaims || supportedClaims<0
                || supportedClaims>reviewedClaims || leakageCount<0)
            throw new IllegalArgumentException("invalid case metrics");
    }
}
