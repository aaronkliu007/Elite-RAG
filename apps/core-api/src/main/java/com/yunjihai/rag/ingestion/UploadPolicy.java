package com.yunjihai.rag.ingestion;

import org.springframework.http.MediaType;

import java.util.Locale;
import java.util.Map;

public final class UploadPolicy {

    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final MediaType PPTX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    private static final Map<String, MediaType> ALLOWED = Map.of(
            "docx", DOCX,
            "xlsx", XLSX,
            "pdf", MediaType.APPLICATION_PDF,
            "pptx", PPTX,
            "md", MediaType.TEXT_MARKDOWN,
            "png", MediaType.IMAGE_PNG);

    private final long maxBytes;
    private final int maxArchiveEntries;
    private final long maxExpandedBytes;
    private final int maxCompressionRatio;

    public UploadPolicy(long maxBytes, int maxArchiveEntries, long maxExpandedBytes, int maxCompressionRatio) {
        if (maxBytes < 1 || maxArchiveEntries < 1 || maxExpandedBytes < 1 || maxCompressionRatio < 1) {
            throw new IllegalArgumentException("上传策略阈值必须为正数");
        }
        this.maxBytes = maxBytes;
        this.maxArchiveEntries = maxArchiveEntries;
        this.maxExpandedBytes = maxExpandedBytes;
        this.maxCompressionRatio = maxCompressionRatio;
    }

    public static UploadPolicy defaults() {
        return new UploadPolicy(100L * 1024 * 1024, 10_000, 500L * 1024 * 1024, 100);
    }

    public long maxBytes() {
        return maxBytes;
    }

    public void validateMetadata(UploadCommand command) {
        if (command == null || command.stream() == null || command.spaceId() == null || command.actorId() == null) {
            throw new UploadRejectedException("上传参数不完整");
        }
        if (command.declaredLength() <= 0 || command.declaredLength() > maxBytes) {
            throw new UploadRejectedException("文件为空或超过大小限制");
        }
        var expected = expectedMediaType(command.originalFilename());
        if (command.declaredMediaType() == null || !expected.isCompatibleWith(command.declaredMediaType())) {
            throw new UploadRejectedException("文件扩展名与声明 MIME 不一致");
        }
    }

    public void validateInspection(UploadCommand command, UploadInspection inspection) {
        if (inspection == null || inspection.detectedMediaType() == null) {
            throw new UploadRejectedException("无法识别文件类型");
        }
        var expected = expectedMediaType(command.originalFilename());
        if (!expected.isCompatibleWith(inspection.detectedMediaType())) {
            throw new UploadRejectedException("文件真实类型与扩展名不一致");
        }
        if (inspection.encrypted()) {
            throw new UploadRejectedException("暂不接受加密文档");
        }
        if (inspection.archiveBomb()) {
            throw new UploadRejectedException("压缩文档展开规模超过安全限制");
        }
        if (inspection.malicious()) {
            throw new UploadRejectedException("文件未通过恶意内容扫描");
        }
    }

    public int maxArchiveEntries() {
        return maxArchiveEntries;
    }

    public long maxExpandedBytes() {
        return maxExpandedBytes;
    }

    public int maxCompressionRatio() {
        return maxCompressionRatio;
    }

    private static MediaType expectedMediaType(String filename) {
        if (filename == null) {
            throw new UploadRejectedException("文件名不能为空");
        }
        var dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            throw new UploadRejectedException("文件缺少受支持的扩展名");
        }
        var mediaType = ALLOWED.get(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (mediaType == null) {
            throw new UploadRejectedException("不支持此文件格式");
        }
        return mediaType;
    }
}
