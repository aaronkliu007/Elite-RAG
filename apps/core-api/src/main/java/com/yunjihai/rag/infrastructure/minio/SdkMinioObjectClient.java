package com.yunjihai.rag.infrastructure.minio;

import com.yunjihai.rag.ingestion.MinioObjectClient;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;

import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class SdkMinioObjectClient implements MinioObjectClient {
    private static final Pattern CONTENT_KEY = Pattern.compile("sha256/([0-9a-f]{2})/([0-9a-f]{64})");
    private final MinioClient client;

    public SdkMinioObjectClient(MinioClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public boolean putIfAbsent(
            String bucket, String key, InputStream stream, long length, String contentType) {
        Objects.requireNonNull(stream);
        if (bucket == null || bucket.isBlank() || contentType == null || contentType.isBlank() || length < 0) {
            throw new IllegalArgumentException("MINIO_OBJECT_ARGUMENT_INVALID");
        }
        String digest = digestFrom(key);
        var args = PutObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .stream(stream, length, -1)
                .contentType(contentType)
                .headers(Map.of("If-None-Match", "*"))
                .userMetadata(Map.of("sha256", digest))
                .build();
        try {
            client.putObject(args);
            return true;
        } catch (ErrorResponseException failure) {
            if (isConditionalConflict(failure)) return verifyExisting(bucket, key, length, contentType, digest);
            throw storageFailure();
        } catch (Exception uncertainFailure) {
            return verifyAfterUncertainWrite(bucket, key, length, contentType, digest);
        }
    }

    private boolean verifyAfterUncertainWrite(
            String bucket, String key, long length, String contentType, String digest) {
        try {
            return verifyExisting(bucket, key, length, contentType, digest);
        } catch (IllegalStateException reconciliationFailure) {
            if ("MINIO_OBJECT_CONFLICT".equals(reconciliationFailure.getMessage())) throw reconciliationFailure;
            throw storageFailure();
        }
    }

    private boolean verifyExisting(
            String bucket, String key, long length, String contentType, String digest) {
        try {
            var existing = client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            String storedDigest = existing.userMetadata().get("sha256");
            if (existing.size() != length || !contentType.equals(existing.contentType())
                    || !digest.equals(storedDigest)) {
                throw conflict();
            }
            return false;
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw storageFailure();
        }
    }

    private static boolean isConditionalConflict(ErrorResponseException failure) {
        String code = failure.errorResponse() == null ? null : failure.errorResponse().code();
        return "PreconditionFailed".equals(code) || "ConditionalRequestConflict".equals(code);
    }

    private static String digestFrom(String key) {
        if (key == null) throw new IllegalArgumentException("MINIO_OBJECT_ARGUMENT_INVALID");
        var match = CONTENT_KEY.matcher(key);
        if (!match.matches() || !match.group(1).equals(match.group(2).substring(0, 2))) {
            throw new IllegalArgumentException("MINIO_OBJECT_ARGUMENT_INVALID");
        }
        return match.group(2);
    }

    private static IllegalStateException conflict() {
        return new IllegalStateException("MINIO_OBJECT_CONFLICT", null);
    }

    private static IllegalStateException storageFailure() {
        return new IllegalStateException("MINIO_OBJECT_WRITE_FAILED", null);
    }
}
