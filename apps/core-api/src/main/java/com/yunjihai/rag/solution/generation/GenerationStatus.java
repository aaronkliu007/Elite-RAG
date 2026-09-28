package com.yunjihai.rag.solution.generation;

public enum GenerationStatus {
    PENDING,
    RETRIEVING,
    GENERATING,
    VERIFYING,
    SUCCEEDED,
    INSUFFICIENT_EVIDENCE,
    FAILED_RETRYABLE,
    FAILED_MANUAL,
    CANCELLED
}
