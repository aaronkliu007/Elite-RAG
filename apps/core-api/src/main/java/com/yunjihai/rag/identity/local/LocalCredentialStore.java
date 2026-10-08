package com.yunjihai.rag.identity.local;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LocalCredentialStore {
    Optional<LocalCredential> lockForAuthentication(String normalizedUsername);
    void insert(LocalCredential credential);
    void updateAuthenticationState(UUID userId, int failures, Instant windowStartedAt, Instant lockedUntil, Instant at);
    void resetAuthenticationState(UUID userId, Instant at);
    void replacePassword(UUID userId, String passwordHash, Instant at);
}
