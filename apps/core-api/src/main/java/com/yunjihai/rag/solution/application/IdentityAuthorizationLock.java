package com.yunjihai.rag.solution.application;

import java.util.UUID;

/** Keeps the actor's organization membership and active status stable until transaction completion. */
@FunctionalInterface
public interface IdentityAuthorizationLock {
    void lock(UUID actorId);
}
