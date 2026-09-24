package com.yunjihai.rag.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record SpooledUpload(Path path, long length, String sha256) implements AutoCloseable {

    @Override
    public void close() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("隔离上传临时文件清理失败", exception);
        }
    }
}
