package com.yunjihai.rag.qa.model;

import com.yunjihai.rag.qa.domain.CandidateAnswer;

import java.util.Objects;
import java.util.regex.Pattern;

public record ModelGeneration(
        CandidateAnswer answer,
        ModelUsage usage,
        String providerRequestId,
        int retryCount) {
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public ModelGeneration {
        Objects.requireNonNull(answer, "answer must not be null");
        Objects.requireNonNull(usage, "usage must not be null");
        if (providerRequestId == null || !REQUEST_ID.matcher(providerRequestId).matches()) {
            throw new IllegalArgumentException("provider request id is invalid");
        }
        if (retryCount < 0 || retryCount > 1) {
            throw new IllegalArgumentException("retry count is invalid");
        }
    }
}
