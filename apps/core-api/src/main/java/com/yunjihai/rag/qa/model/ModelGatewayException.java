package com.yunjihai.rag.qa.model;

import java.util.Objects;

public final class ModelGatewayException extends RuntimeException {
    public enum Kind {
        AUTHENTICATION,
        RATE_LIMITED,
        TIMEOUT,
        INVALID_RESPONSE,
        UNAVAILABLE,
        POLICY_VIOLATION
    }

    private final Kind kind;
    private final int retryCount;

    public ModelGatewayException(Kind kind) {
        this(kind, 0);
    }

    public ModelGatewayException(Kind kind, int retryCount) {
        super("MODEL_GATEWAY_" + Objects.requireNonNull(kind, "kind must not be null").name());
        if (retryCount < 0 || retryCount > 1) {
            throw new IllegalArgumentException("retry count is invalid");
        }
        this.kind = kind;
        this.retryCount = retryCount;
    }

    public Kind kind() {
        return kind;
    }

    public int retryCount() {
        return retryCount;
    }

    public ModelGatewayException withRetryCount(int value) {
        return retryCount == value ? this : new ModelGatewayException(kind, value);
    }
}
