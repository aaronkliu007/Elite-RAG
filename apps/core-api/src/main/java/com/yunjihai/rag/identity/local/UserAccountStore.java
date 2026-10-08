package com.yunjihai.rag.identity.local;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountStore {
    void insert(UserAccount account, String provider, String subject);
    Optional<UserAccount> findById(UUID userId);
    List<UserAccount> list();
    void setEnabled(UUID userId, boolean enabled, Instant at);
}
