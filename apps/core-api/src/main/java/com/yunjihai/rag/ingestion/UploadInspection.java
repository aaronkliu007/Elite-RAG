package com.yunjihai.rag.ingestion;

import org.springframework.http.MediaType;

public record UploadInspection(
        MediaType detectedMediaType,
        boolean encrypted,
        boolean archiveBomb,
        boolean malicious,
        String detail) {

    public static UploadInspection safe(MediaType mediaType) {
        return new UploadInspection(mediaType, false, false, false, "safe");
    }
}
