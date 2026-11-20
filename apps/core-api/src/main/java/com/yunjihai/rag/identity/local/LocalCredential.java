package com.yunjihai.rag.identity.local;

import java.time.Instant;
import java.util.UUID;

public record LocalCredential(
        UUID userId,
        String normalizedUsername,
        String passwordHash,
        int failedAttempts,
        Instant failureWindowStartedAt,
        Instant lockedUntil,
        Instant passwordChangedAt,
        boolean passwordChangeRequired) {
    public LocalCredential(UUID userId, String normalizedUsername, String passwordHash, int failedAttempts,
                           Instant failureWindowStartedAt, Instant lockedUntil, Instant passwordChangedAt) {
        this(userId, normalizedUsername, passwordHash, failedAttempts, failureWindowStartedAt, lockedUntil, passwordChangedAt, false);
    }
}
