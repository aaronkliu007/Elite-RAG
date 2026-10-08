package com.yunjihai.rag.identity;

import java.util.UUID;

public interface SessionVersionStore {

    long currentVersion(UUID userId);

    long advanceVersion(UUID userId);

    boolean isEnabled(UUID userId);

    void setEnabled(UUID userId, boolean enabled);
}
