package com.yunjihai.rag.qa;

import java.time.Instant;
import java.util.UUID;

/** Performs one final authoritative check over every persisted evidence version. */
@FunctionalInterface
public interface QaEvidenceAuthorizationPort {
    boolean allAuthorized(UUID requestId, UUID actorId, Instant at);
}
