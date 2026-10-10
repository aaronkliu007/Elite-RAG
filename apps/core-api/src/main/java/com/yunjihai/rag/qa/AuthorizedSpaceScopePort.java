package com.yunjihai.rag.qa;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@FunctionalInterface
public interface AuthorizedSpaceScopePort {
    Set<UUID> resolve(UUID actorId, Instant at);
}
