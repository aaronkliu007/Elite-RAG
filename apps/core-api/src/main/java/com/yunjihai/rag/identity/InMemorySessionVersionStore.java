package com.yunjihai.rag.identity;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

public final class InMemorySessionVersionStore implements SessionVersionStore {

    private final ConcurrentHashMap<UUID, AtomicLong> versions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> enabled = new ConcurrentHashMap<>();

    @Override
    public long currentVersion(UUID userId) {
        return versions.computeIfAbsent(userId, ignored -> new AtomicLong()).get();
    }

    @Override
    public long advanceVersion(UUID userId) {
        return versions.computeIfAbsent(userId, ignored -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public boolean isEnabled(UUID userId) {
        return enabled.getOrDefault(userId, true);
    }

    @Override
    public void setEnabled(UUID userId, boolean value) {
        enabled.put(userId, value);
    }
}
