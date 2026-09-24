package com.yunjihai.rag.ingestion;

import org.springframework.http.MediaType;

import java.io.InputStream;
import java.util.UUID;

public record UploadCommand(
        UUID spaceId,
        String title,
        String originalFilename,
        MediaType declaredMediaType,
        long declaredLength,
        InputStream stream,
        UUID actorId) {
}
