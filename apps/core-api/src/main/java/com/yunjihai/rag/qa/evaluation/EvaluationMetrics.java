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
        int leakageCount,
        Integer reviewedClaimCount,
        Integer generatedClaimCount,
        Integer irrelevantClaimCount) {
    public EvaluationMetrics(int expectedEvidenceCount,int retrievedExpectedEvidenceCount,int citationCount,
                             int validCitationCount,int claimCount,int supportedClaimCount,int refusalCaseCount,
                             int correctlyRefusedCount,int leakageCount,Integer reviewedClaimCount,Integer generatedClaimCount) {
        this(expectedEvidenceCount,retrievedExpectedEvidenceCount,citationCount,validCitationCount,claimCount,
                supportedClaimCount,refusalCaseCount,correctlyRefusedCount,leakageCount,reviewedClaimCount,generatedClaimCount,0);
    }
    public EvaluationMetrics(int expectedEvidenceCount,int retrievedExpectedEvidenceCount,int citationCount,
                             int validCitationCount,int claimCount,int supportedClaimCount,
                             int refusalCaseCount,int correctlyRefusedCount,int leakageCount) {
        this(expectedEvidenceCount,retrievedExpectedEvidenceCount,citationCount,validCitationCount,
                claimCount,supportedClaimCount,refusalCaseCount,correctlyRefusedCount,leakageCount,0,0);
    }
    public EvaluationMetrics(int expectedEvidenceCount,int retrievedExpectedEvidenceCount,int citationCount,
                             int validCitationCount,int claimCount,int supportedClaimCount,
                             int refusalCaseCount,int correctlyRefusedCount,int leakageCount,int reviewedClaimCount) {
        this(expectedEvidenceCount,retrievedExpectedEvidenceCount,citationCount,validCitationCount,
                claimCount,supportedClaimCount,refusalCaseCount,correctlyRefusedCount,leakageCount,
                reviewedClaimCount,claimCount);
    }
    public EvaluationMetrics {
        reviewedClaimCount=reviewedClaimCount==null?0:reviewedClaimCount;
        generatedClaimCount=generatedClaimCount==null?0:generatedClaimCount;
        irrelevantClaimCount=irrelevantClaimCount==null?0:irrelevantClaimCount;
        int[] values={expectedEvidenceCount,retrievedExpectedEvidenceCount,citationCount,validCitationCount,
                claimCount,supportedClaimCount,refusalCaseCount,correctlyRefusedCount,leakageCount,
                reviewedClaimCount,generatedClaimCount,irrelevantClaimCount};
        for(int value:values)if(value<0)throw new IllegalArgumentException("evaluation metric count must not be negative");
        if(retrievedExpectedEvidenceCount>expectedEvidenceCount||validCitationCount>citationCount
                ||supportedClaimCount>claimCount||generatedClaimCount>claimCount
                ||reviewedClaimCount>generatedClaimCount||irrelevantClaimCount>reviewedClaimCount
                ||correctlyRefusedCount>refusalCaseCount)
            throw new IllegalArgumentException("evaluation metric numerator exceeds denominator");
    }

    public double recallAt10() { return ratio(retrievedExpectedEvidenceCount,expectedEvidenceCount); }
    public double citationValidity() { return ratio(validCitationCount,citationCount); }
    public double claimSupport() { return ratio(supportedClaimCount,claimCount); }
    public double claimReviewCoverage() { return ratio(reviewedClaimCount,generatedClaimCount); }
    public double correctRefusalRate() { return ratio(correctlyRefusedCount,refusalCaseCount); }
    private static double ratio(int numerator,int denominator) { return denominator==0?0d:(double)numerator/denominator; }
}
