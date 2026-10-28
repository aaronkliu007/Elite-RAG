package com.yunjihai.rag.qa;

import java.util.UUID;

@FunctionalInterface
public interface QaActorRateLimiter {
    void acquire(UUID actorId);
}
