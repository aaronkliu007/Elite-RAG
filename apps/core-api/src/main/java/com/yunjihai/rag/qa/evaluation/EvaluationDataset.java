package com.yunjihai.rag.qa.evaluation;

import java.util.*;
import java.util.regex.Pattern;

public record EvaluationDataset(
        UUID id,
        String version,
        String promptVersion,
        String sanitizationVersion,
        List<EvaluationCase> cases,
        boolean active) {
    private static final Pattern VERSION=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public EvaluationDataset {
        Objects.requireNonNull(id,"dataset id must not be null");
        requireVersion(version,"dataset version");
        requireVersion(promptVersion,"prompt version");
        requireVersion(sanitizationVersion,"sanitization version");
        cases=List.copyOf(Objects.requireNonNull(cases));
        if(cases.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("dataset case must not be null");
        if(cases.stream().map(EvaluationCase::id).distinct().count()!=cases.size())
            throw new IllegalArgumentException("evaluation case ids must be unique");
        if(active&&cases.size()<50)throw new IllegalStateException("EVALUATION_DATASET_REQUIRES_50_CASES");
    }

    public EvaluationDataset activate() {
        if(cases.size()<50)throw new IllegalStateException("EVALUATION_DATASET_REQUIRES_50_CASES");
        return new EvaluationDataset(id,version,promptVersion,sanitizationVersion,cases,true);
    }

    private static void requireVersion(String value,String field) {
        if(value==null||!VERSION.matcher(value).matches())throw new IllegalArgumentException(field+" is invalid");
    }
}
