package com.yunjihai.rag.ingestion;

import java.util.UUID;

public record ValidatedUpload(
        UUID spaceId,
        String title,
        String originalFilename,
        UUID actorId,
        StoredBlob blob) {
}
