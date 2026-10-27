package com.yunjihai.rag.qa.evaluation;

public record EvaluationMetrics(
        int expectedEvidenceCount,
        int retrievedExpectedEvidenceCount,
        int citationCount,
        int validCitationCount,
        int claimCount,
        int supportedClaimCount,
        int refusalCaseCount,
        int correctlyRefusedCount,
        int leakageCount) {
    public EvaluationMetrics {
        int[] values={expectedEvidenceCount,retrievedExpectedEvidenceCount,citationCount,validCitationCount,
                claimCount,supportedClaimCount,refusalCaseCount,correctlyRefusedCount,leakageCount};
        for(int value:values)if(value<0)throw new IllegalArgumentException("evaluation metric count must not be negative");
        if(retrievedExpectedEvidenceCount>expectedEvidenceCount||validCitationCount>citationCount
                ||supportedClaimCount>claimCount||correctlyRefusedCount>refusalCaseCount)
            throw new IllegalArgumentException("evaluation metric numerator exceeds denominator");
    }

    public double recallAt10() { return ratio(retrievedExpectedEvidenceCount,expectedEvidenceCount); }
    public double citationValidity() { return ratio(validCitationCount,citationCount); }
    public double claimSupport() { return ratio(supportedClaimCount,claimCount); }
    public double correctRefusalRate() { return ratio(correctlyRefusedCount,refusalCaseCount); }
    private static double ratio(int numerator,int denominator) { return denominator==0?1d:(double)numerator/denominator; }
}
