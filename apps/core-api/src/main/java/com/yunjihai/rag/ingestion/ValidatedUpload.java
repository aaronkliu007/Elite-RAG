package com.yunjihai.rag.ingestion;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ValidatedUpload(
        UUID spaceId,
        String title,
        String classification,
        List<String> tags,
        String originalFilename,
        UUID actorId,
        StoredBlob blob) {
    public ValidatedUpload {
        tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
    }

    public ValidatedUpload(
            UUID spaceId,
            String title,
            String originalFilename,
            UUID actorId,
            StoredBlob blob) {
        this(spaceId, title, null, List.of(), originalFilename, actorId, blob);
    }
}
