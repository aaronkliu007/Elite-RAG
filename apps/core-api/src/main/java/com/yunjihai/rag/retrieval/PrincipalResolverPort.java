package com.yunjihai.rag.retrieval;

import com.yunjihai.rag.access.PrincipalRef;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@FunctionalInterface
public interface PrincipalResolverPort {

    Set<PrincipalRef> resolve(UUID userId, Instant at);
}
