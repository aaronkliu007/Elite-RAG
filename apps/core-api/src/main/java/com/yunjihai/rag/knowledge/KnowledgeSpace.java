package com.yunjihai.rag.knowledge;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeSpace(UUID id, String name, UUID createdBy, Instant createdAt) {
}
