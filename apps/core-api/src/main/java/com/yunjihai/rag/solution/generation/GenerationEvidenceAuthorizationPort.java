package com.yunjihai.rag.solution.generation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface GenerationEvidenceAuthorizationPort {
    void requireReadable(UUID actorId, UUID spaceId, List<GenerationEvidenceSource> evidence, Instant at);
}
