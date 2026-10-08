package com.yunjihai.rag.api.idempotency;

import java.util.Objects;
import java.util.UUID;

public record IdempotencyScope(UUID actorId, String operation, String key) {
    public IdempotencyScope {
        actorId = Objects.requireNonNull(actorId);
        operation = requireOperation(operation);
        key = requireKey(key);
    }

    private static String requireOperation(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("operation is required");
        String normalized = value.trim();
        if (normalized.length() > 100) throw new IllegalArgumentException("operation is too long");
        return normalized;
    }

    private static String requireKey(String value) {
        if (value == null || value.isEmpty() || value.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must contain 1 to 128 characters");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                throw new IllegalArgumentException("Idempotency-Key must contain printable ASCII only");
            }
        }
        return value;
    }
}
