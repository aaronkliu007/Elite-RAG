package com.yunjihai.rag.ingestion;

import org.springframework.http.MediaType;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record UploadCommand(
        UUID spaceId,
        String title,
        String classification,
        List<String> tags,
        String originalFilename,
        MediaType declaredMediaType,
        long declaredLength,
        InputStream stream,
        UUID actorId) {
    public UploadCommand {
        tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
    }

    public UploadCommand(
            UUID spaceId,
            String title,
            String originalFilename,
            MediaType declaredMediaType,
            long declaredLength,
            InputStream stream,
            UUID actorId) {
        this(spaceId, title, null, List.of(), originalFilename,
                declaredMediaType, declaredLength, stream, actorId);
    }
}
