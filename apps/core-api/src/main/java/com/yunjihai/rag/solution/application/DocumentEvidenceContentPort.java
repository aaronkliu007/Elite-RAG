package com.yunjihai.rag.solution.application;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface DocumentEvidenceContentPort {
    Optional<String> readExcerpt(UUID spaceId, UUID documentId, UUID versionId, String nodeId);
}
