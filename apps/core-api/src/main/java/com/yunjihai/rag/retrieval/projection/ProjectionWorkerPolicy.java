package com.yunjihai.rag.retrieval.projection;

import java.time.Duration;
import java.util.Objects;

public record ProjectionWorkerPolicy(
        int claimBatchSize,
        int spacePageSize,
        Duration leaseDuration,
        int maxAttempts,
        Duration initialRetryDelay,
        Duration maxRetryDelay) {

    public ProjectionWorkerPolicy {
        Objects.requireNonNull(leaseDuration);
        Objects.requireNonNull(initialRetryDelay);
        Objects.requireNonNull(maxRetryDelay);
        if (claimBatchSize < 1 || claimBatchSize > 1_000
                || spacePageSize < 1 || spacePageSize > 1_000
                || leaseDuration.isZero() || leaseDuration.isNegative()
                || maxAttempts < 1
                || initialRetryDelay.isZero() || initialRetryDelay.isNegative()
                || maxRetryDelay.compareTo(initialRetryDelay) < 0) {
            throw new IllegalArgumentException("projection worker policy is invalid");
        }
    }

    public Duration retryDelay(int attempt) {
        if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        Duration delay = initialRetryDelay;
        for (int index = 1; index < attempt && delay.compareTo(maxRetryDelay) < 0; index++) {
            if (delay.compareTo(maxRetryDelay.dividedBy(2)) > 0) return maxRetryDelay;
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maxRetryDelay) > 0 ? maxRetryDelay : delay;
    }
}
