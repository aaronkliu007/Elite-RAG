package com.yunjihai.rag.infrastructure.minio;

import com.yunjihai.rag.ingestion.UploadPolicy;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;

import java.util.Objects;
import java.util.regex.Pattern;

/** 只允许读取内容寻址对象，并对响应大小执行硬限制。 */
public final class MinioBlobReader {

    private static final long MAX_BYTES = UploadPolicy.defaults().maxBytes();
    private static final Pattern CONTENT_KEY = Pattern.compile(
            "sha256/([0-9a-f]{2})/([0-9a-f]{64})");

    private final MinioClient client;
    private final String bucket;

    public MinioBlobReader(MinioClient client, String bucket) {
        this.client = Objects.requireNonNull(client);
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("MINIO_BUCKET_INVALID", null);
        }
        this.bucket = bucket;
    }

    public byte[] read(String objectKey, long maxBytes) {
        validateObjectKey(objectKey);
        if (maxBytes < 1 || maxBytes > MAX_BYTES) {
            throw new IllegalArgumentException("MINIO_READ_LIMIT_INVALID", null);
        }
        try (var input = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            var bytes = input.readNBytes(Math.toIntExact(maxBytes) + 1);
            if (bytes.length > maxBytes) {
                throw new IllegalStateException("MINIO_OBJECT_TOO_LARGE", null);
            }
            return bytes;
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("MINIO_OBJECT_READ_FAILED", null);
        }
    }

    private static void validateObjectKey(String objectKey) {
        if (objectKey == null) throw new IllegalArgumentException("MINIO_OBJECT_KEY_INVALID", null);
        var match = CONTENT_KEY.matcher(objectKey);
        if (!match.matches() || !match.group(1).equals(match.group(2).substring(0, 2))) {
            throw new IllegalArgumentException("MINIO_OBJECT_KEY_INVALID", null);
        }
    }
}
