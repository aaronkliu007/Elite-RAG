package com.yunjihai.rag.knowledge;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DocumentVersion(
        UUID id,
        int versionNumber,
        String sourceBlobId,
        DocumentStatus status,
        ParseStatus parseStatus,
        UUID createdBy,
        Instant createdAt,
        UUID statusChangedBy,
        Instant statusChangedAt) {

    public DocumentVersion {
        Objects.requireNonNull(id);
        Objects.requireNonNull(status);
        Objects.requireNonNull(parseStatus);
        Objects.requireNonNull(createdBy);
        Objects.requireNonNull(createdAt);
        if (versionNumber < 1 || sourceBlobId == null || sourceBlobId.isBlank()) {
            throw new IllegalArgumentException("文档版本参数无效");
        }
    }

    static DocumentVersion draft(int versionNumber, String sourceBlobId, UUID actorId, Instant at) {
        return new DocumentVersion(
                UUID.randomUUID(), versionNumber, sourceBlobId, DocumentStatus.DRAFT,
                ParseStatus.PENDING, actorId, at, actorId, at);
    }

    DocumentVersion withStatus(DocumentStatus next, UUID actorId, Instant at) {
        return new DocumentVersion(
                id, versionNumber, sourceBlobId, next, parseStatus,
                createdBy, createdAt, actorId, at);
    }

    DocumentVersion withParseStatus(ParseStatus next, Instant at) {
        return new DocumentVersion(
                id, versionNumber, sourceBlobId, status, next,
                createdBy, createdAt, statusChangedBy, at);
    }
}
