package com.yunjihai.rag.api;

import java.util.Map;
import java.util.Objects;

public record ApiError(
        String code,
        String message,
        String traceId,
        Map<String, String> fieldErrors) {
    public ApiError {
        code = Objects.requireNonNull(code);
        message = Objects.requireNonNull(message);
        traceId = Objects.requireNonNull(traceId);
        fieldErrors = fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors);
    }
}
