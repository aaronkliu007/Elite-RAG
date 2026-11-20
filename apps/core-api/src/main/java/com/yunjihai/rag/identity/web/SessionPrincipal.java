package com.yunjihai.rag.identity.web;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

public record SessionPrincipal(
        UUID userId,
        String username,
        String displayName,
        long sessionVersion,
        Instant issuedAt,
        boolean passwordChangeRequired) implements Principal {

    public SessionPrincipal(UUID userId, String username, String displayName, long sessionVersion, Instant issuedAt) {
        this(userId, username, displayName, sessionVersion, issuedAt, false);
    }

    @Override
    public String getName() {
        return userId.toString();
    }
}
