package com.yunjihai.rag.qa;

public final class QaRateLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;

    public QaRateLimitExceededException(long retryAfterSeconds) {
        super("QA_RATE_LIMITED");
        if (retryAfterSeconds < 1) {
            throw new IllegalArgumentException("retryAfterSeconds must be positive");
        }
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
