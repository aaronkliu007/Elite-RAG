package com.yunjihai.rag.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class SpooledUpload implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(SpooledUpload.class.getName());

    private final Path path;
    private final long length;
    private final String sha256;
    private final QuarantineFileCleaner cleaner;

    public SpooledUpload(Path path, long length, String sha256) {
        this(path, length, sha256, Files::deleteIfExists);
    }

    SpooledUpload(Path path, long length, String sha256, QuarantineFileCleaner cleaner) {
        this.path = Objects.requireNonNull(path);
        this.length = length;
        this.sha256 = Objects.requireNonNull(sha256);
        this.cleaner = Objects.requireNonNull(cleaner);
    }

    public Path path() {
        return path;
    }

    public long length() {
        return length;
    }

    public String sha256() {
        return sha256;
    }

    @Override
    public void close() {
        try {
            cleaner.delete(path);
        } catch (IOException | SecurityException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "隔离上传临时文件清理失败: " + path, exception);
        }
    }
}
