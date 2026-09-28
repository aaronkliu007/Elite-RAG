package com.yunjihai.rag.solution.finalization;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@FunctionalInterface
public interface EvidenceAccessPort {

    Set<UUID> authorize(UUID userId, UUID spaceId, Set<UUID> evidenceIds, Instant at);
}
