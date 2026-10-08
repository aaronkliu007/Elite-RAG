package com.yunjihai.rag.knowledge;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeSpace(
        UUID id,
        String name,
        String description,
        long revision,
        UUID createdBy,
        Instant createdAt) {
    public KnowledgeSpace(UUID id, String name, UUID createdBy, Instant createdAt) {
        this(id, name, "", 0, createdBy, createdAt);
    }
}
