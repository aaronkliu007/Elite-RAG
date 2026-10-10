package com.yunjihai.rag.qa.model;

import java.time.Duration;
import java.util.Objects;

public record ModelUsage(int inputTokens, int outputTokens, Duration latency) {
    public ModelUsage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token usage must not be negative");
        }
        Objects.requireNonNull(latency, "latency must not be null");
        if (latency.isNegative()) {
            throw new IllegalArgumentException("latency must not be negative");
        }
    }

    public int totalTokens() {
        return Math.addExact(inputTokens, outputTokens);
    }
}
