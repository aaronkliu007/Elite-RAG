package com.yunjihai.rag.ingestion;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ParserJob(
        String schemaVersion,
        UUID jobId,
        String filename,
        String mediaType,
        long sizeBytes,
        String sha256,
        int timeoutSeconds) {

    private static final Set<String> SUPPORTED_MEDIA_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/markdown",
            "image/png");

    public ParserJob {
        if (!"parser-job-v1".equals(schemaVersion)) {
            throw new IllegalArgumentException("解析任务版本无效");
        }
        Objects.requireNonNull(jobId);
        if (filename == null || filename.isBlank() || filename.length() > 255
                || filename.contains("/") || filename.contains("\\")
                || filename.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("解析文件名无效");
        }
        if (!SUPPORTED_MEDIA_TYPES.contains(mediaType)) {
            throw new IllegalArgumentException("解析媒体类型无效");
        }
        if (sizeBytes < 1 || sizeBytes > 100L * 1024 * 1024) {
            throw new IllegalArgumentException("解析文件大小无效");
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("解析文件哈希无效");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("解析超时参数无效");
        }
    }
}
