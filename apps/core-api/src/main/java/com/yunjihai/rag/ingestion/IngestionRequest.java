package com.yunjihai.rag.ingestion;

import java.util.Objects;
import java.util.UUID;

public record IngestionRequest(
        DocumentVersionId documentVersion,
        UUID actorId,
        String sourceBlobId,
        String filename,
        String mediaType,
        long sizeBytes,
        String sha256) {

    public IngestionRequest {
        Objects.requireNonNull(documentVersion);
        Objects.requireNonNull(actorId);
        if (sourceBlobId == null || sourceBlobId.isBlank()) {
            throw new IllegalArgumentException("入库源 Blob 无效");
        }
        new ParserJob(
                "parser-job-v1",
                UUID.randomUUID(),
                filename,
                mediaType,
                sizeBytes,
                sha256,
                300);
    }
}
