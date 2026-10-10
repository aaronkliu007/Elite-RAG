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

    public ModelGatewayException(Kind kind) {
        super("MODEL_GATEWAY_" + Objects.requireNonNull(kind, "kind must not be null").name());
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
