package com.yunjihai.rag.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record SpooledUpload(Path path, long length, String sha256) implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(SpooledUpload.class.getName());

    @Override
    public void close() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "隔离上传临时文件清理失败: " + path, exception);
        }
    }
}
