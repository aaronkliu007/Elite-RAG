package com.yunjihai.rag.api.idempotency;

import java.util.Objects;

public record StoredHttpResult(int status, String responseBody) {
    public StoredHttpResult {
        if (status < 100 || status > 599) throw new IllegalArgumentException("invalid HTTP status");
        responseBody = Objects.requireNonNull(responseBody);
    }
}
