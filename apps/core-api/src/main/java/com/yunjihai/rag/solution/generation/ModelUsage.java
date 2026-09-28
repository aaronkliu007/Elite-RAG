package com.yunjihai.rag.solution.generation;

public record ModelUsage(
        long inputTokens,
        long outputTokens,
        long costMicros,
        long latencyMillis) {

    public ModelUsage {
        if (inputTokens < 0 || outputTokens < 0 || costMicros < 0 || latencyMillis < 0) {
            throw new IllegalArgumentException("model usage values must not be negative");
        }
    }
}
