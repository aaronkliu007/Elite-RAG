package com.yunjihai.rag.knowledge;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record KnowledgeSpaceView(
        UUID id,
        String name,
        String description,
        long revision,
        UUID createdBy,
        Instant createdAt,
        Set<String> allowedActions) {
    public KnowledgeSpaceView {
        allowedActions = Set.copyOf(allowedActions);
    }
}
