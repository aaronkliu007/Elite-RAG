package com.yunjihai.rag.solution.application;

import java.time.Instant;
import java.util.UUID;

@FunctionalInterface
public interface SolutionDocumentSourcePort {
    boolean isCurrentPublishedAndReadable(UUID actorId, UUID spaceId, UUID documentId, UUID versionId, Instant at);
}
