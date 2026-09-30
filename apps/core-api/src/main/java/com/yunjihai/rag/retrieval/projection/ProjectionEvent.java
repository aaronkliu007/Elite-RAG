package com.yunjihai.rag.retrieval.projection;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProjectionEvent(
        UUID eventId,
        long sequence,
        ProjectionScope scope,
        UUID spaceId,
        UUID documentId,
        UUID versionId,
        Long documentRevision,
        UUID progressDocumentId,
        ProjectionEventStatus status,
        int attempts,
        Instant nextAttemptAt,
        String errorCode,
        Instant createdAt,
        Instant updatedAt) {

    public ProjectionEvent {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(nextAttemptAt);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
        if (sequence <= 0 || attempts < 0) {
            throw new IllegalArgumentException("投影事件序号或尝试次数无效");
        }
        if (scope == ProjectionScope.DOCUMENT && documentId == null) {
            throw new IllegalArgumentException("文档投影事件必须包含文档标识");
        }
        if (scope == ProjectionScope.SPACE
                && (documentId != null || versionId != null || documentRevision != null)) {
            throw new IllegalArgumentException("空间投影事件不能绑定单个文档版本");
        }
    }
}
