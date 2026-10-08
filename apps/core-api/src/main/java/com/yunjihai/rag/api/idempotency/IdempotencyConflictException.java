package com.yunjihai.rag.api.idempotency;

import java.util.Objects;

public final class IdempotencyConflictException extends RuntimeException {
    public enum Reason {
        IN_PROGRESS,
        REQUEST_MISMATCH
    }

    private final Reason reason;

    public IdempotencyConflictException(Reason reason) {
        super(Objects.requireNonNull(reason).name(), null, false, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public String code() {
        return reason == Reason.IN_PROGRESS
                ? "IDEMPOTENCY_IN_PROGRESS"
                : "IDEMPOTENCY_KEY_REUSED";
    }

    public String publicMessage() {
        return reason == Reason.IN_PROGRESS
                ? "相同请求正在处理中"
                : "幂等键已用于不同请求";
    }
}
