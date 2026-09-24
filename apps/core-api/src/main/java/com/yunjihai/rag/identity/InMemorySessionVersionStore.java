package com.yunjihai.rag.identity;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemorySessionVersionStore implements SessionVersionStore {

    private final ConcurrentHashMap<String, AtomicLong> versions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> enabled = new ConcurrentHashMap<>();

    @Override
    public long currentVersion(String userExternalId) {
        return versions.computeIfAbsent(userExternalId, ignored -> new AtomicLong()).get();
    }

    @Override
    public long advanceVersion(String userExternalId) {
        return versions.computeIfAbsent(userExternalId, ignored -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public boolean isEnabled(String userExternalId) {
        return enabled.getOrDefault(userExternalId, true);
    }

    @Override
    public void setEnabled(String userExternalId, boolean value) {
        enabled.put(userExternalId, value);
    }
}
