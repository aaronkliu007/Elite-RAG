package com.yunjihai.rag.identity.web;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

public record SessionPrincipal(
        UUID userId,
        String username,
        String displayName,
        long sessionVersion,
        Instant issuedAt) implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }
}
