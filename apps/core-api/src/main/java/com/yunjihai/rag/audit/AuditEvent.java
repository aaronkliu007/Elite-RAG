package com.yunjihai.rag.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEvent(
        UUID id,
        UUID actorId,
        AuditAction action,
        String resourceType,
        UUID resourceId,
        AuditOutcome outcome,
        Map<String, String> metadata,
        Instant occurredAt) {

    public AuditEvent {
        metadata = Map.copyOf(metadata);
    }
}
