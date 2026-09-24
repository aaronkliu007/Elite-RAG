package com.yunjihai.rag.ingestion;

import org.springframework.http.MediaType;

import java.nio.file.Path;

@FunctionalInterface
public interface UploadSecurityScanner {

    UploadInspection inspect(Path isolatedFile, String originalFilename, MediaType declaredMediaType);
}
