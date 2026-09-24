package com.yunjihai.rag.ingestion;

import org.springframework.http.MediaType;

public record StoredBlob(
        String sha256,
        String objectKey,
        long length,
        MediaType mediaType,
        boolean created) {
}
