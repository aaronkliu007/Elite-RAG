package com.yunjihai.rag.audit;

import java.util.Map;
import java.util.UUID;

public record AuditCommand(
        UUID actorId,
        AuditAction action,
        String resourceType,
        UUID resourceId,
        AuditOutcome outcome,
        Map<String, String> metadata) {
}
