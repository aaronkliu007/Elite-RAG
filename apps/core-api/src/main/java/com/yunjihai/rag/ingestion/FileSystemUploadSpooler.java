package com.yunjihai.rag.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class FileSystemUploadSpooler implements UploadSpooler {

    @Override
    public SpooledUpload spool(InputStream source, long maxBytes) {
        Path path = null;
        try {
            path = Files.createTempFile("rag-upload-quarantine-", ".bin");
            var digest = MessageDigest.getInstance("SHA-256");
            long length;
            try (OutputStream target = Files.newOutputStream(path)) {
                length = copyLimited(source, target, digest, maxBytes);
            }
            if (length == 0) {
                throw new UploadRejectedException("文件为空");
            }
            return new SpooledUpload(path, length, HexFormat.of().formatHex(digest.digest()));
        } catch (UploadRejectedException exception) {
            deleteQuietly(path);
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            deleteQuietly(path);
            throw new IllegalStateException("上传隔离写入失败", exception);
        }
    }

    private static long copyLimited(
            InputStream source,
            OutputStream target,
            MessageDigest digest,
            long maxBytes) throws IOException {
        var buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = source.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new UploadRejectedException("文件实际大小超过限制");
            }
            digest.update(buffer, 0, read);
            target.write(buffer, 0, read);
        }
        return total;
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // 原始异常优先；生产环境由临时目录清理任务兜底。
            }
        }
    }
}
