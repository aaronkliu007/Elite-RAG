package com.yunjihai.rag.access;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@FunctionalInterface
public interface AccessDecisionRepository {

    Optional<AccessSnapshot> findReadDecision(
            UUID spaceId,
            UUID documentId,
            Set<PrincipalRef> principals,
            Instant at);
}
