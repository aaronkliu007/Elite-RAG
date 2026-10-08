package com.yunjihai.rag.identity.local;

import java.time.Instant;
import java.util.UUID;

public record UserAccount(
        UUID id,
        String username,
        String displayName,
        boolean enabled,
        long sessionVersion,
        Instant createdAt,
        Instant updatedAt) {
}
