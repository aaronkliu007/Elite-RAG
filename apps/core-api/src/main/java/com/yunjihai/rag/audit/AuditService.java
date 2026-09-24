package com.yunjihai.rag.audit;

import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class AuditService implements AuditSink {

    private static final Set<String> ALLOWED_METADATA_KEYS = Set.of(
            "jobId",
            "attempt",
            "errorCode",
            "versionId",
            "spaceId",
            "permissionScope",
            "targetPrincipalType",
            "targetPrincipalId",
            "source");

    private final AuditEventStore store;
    private final Clock clock;

    public AuditService(AuditEventStore store, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    @Override
    public AuditEvent record(AuditCommand command) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(command.actorId());
        Objects.requireNonNull(command.action());
        Objects.requireNonNull(command.resourceId());
        Objects.requireNonNull(command.outcome());
        if (command.resourceType() == null
                || !command.resourceType().matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("审计资源类型无效");
        }
        var metadata = validateMetadata(command.metadata());
        var event = new AuditEvent(
                UUID.randomUUID(),
                command.actorId(),
                command.action(),
                command.resourceType(),
                command.resourceId(),
                command.outcome(),
                metadata,
                Instant.now(clock));
        store.append(event);
        return event;
    }

    private static Map<String, String> validateMetadata(Map<String, String> metadata) {
        Objects.requireNonNull(metadata);
        for (var entry : metadata.entrySet()) {
            if (!ALLOWED_METADATA_KEYS.contains(entry.getKey())) {
                throw new IllegalArgumentException("审计元数据包含敏感或未批准字段");
            }
            if (entry.getValue() == null || entry.getValue().length() > 200) {
                throw new IllegalArgumentException("审计元数据值无效");
            }
        }
        return Map.copyOf(metadata);
    }
}
