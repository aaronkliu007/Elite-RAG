package com.yunjihai.rag.infrastructure.minio;

import com.yunjihai.rag.ingestion.MinioObjectClient;
import com.yunjihai.rag.ingestion.UploadPolicy;
import io.minio.MinioAsyncClient;
import io.minio.errors.ErrorResponseException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Objects;
import java.util.regex.Pattern;

public final class SdkMinioObjectClient implements MinioObjectClient {
    static final int PART_SIZE_BYTES = 5 * 1024 * 1024;
    static final long MAX_OBJECT_BYTES = UploadPolicy.defaults().maxBytes();
    private static final Pattern CONTENT_KEY = Pattern.compile("sha256/([0-9a-f]{2})/([0-9a-f]{64})");
    private final MinioMultipartOperations operations;

    public SdkMinioObjectClient(MinioAsyncClient client) {
        this((MinioMultipartOperations) new PinnedMinioMultipartClient(Objects.requireNonNull(client)));
    }

    SdkMinioObjectClient(MinioMultipartOperations operations) {
        this.operations = Objects.requireNonNull(operations);
    }

    @Override
    public boolean putIfAbsent(
            String bucket, String key, InputStream stream, long length, String contentType) {
        Objects.requireNonNull(stream);
        if (bucket == null || bucket.isBlank() || contentType == null || contentType.isBlank() || length <= 0) {
            throw new IllegalArgumentException("MINIO_OBJECT_ARGUMENT_INVALID");
        }
        if (length > MAX_OBJECT_BYTES) {
            throw new IllegalArgumentException("MINIO_OBJECT_TOO_LARGE", null);
        }
        String digest = digestFrom(key);
        String uploadId = null;
        boolean completionAttempted = false;
        try {
            uploadId = operations.begin(bucket, key, contentType, digest);
            var parts = uploadParts(bucket, key, uploadId, stream, length);
            completionAttempted = true;
            operations.completeIfAbsent(bucket, key, uploadId, parts);
            return true;
        } catch (ErrorResponseException failure) {
            boolean aborted = abortQuietly(bucket, key, uploadId);
            if (completionAttempted && isConditionalConflict(failure)) {
                if (!aborted) throw withAbortFailure(storageFailure());
                return verifyExisting(bucket, key, length, contentType, digest);
            }
            throw aborted ? storageFailure() : withAbortFailure(storageFailure());
        } catch (Exception failure) {
            boolean aborted = abortQuietly(bucket, key, uploadId);
            var safe = completionAttempted ? uncertainWriteFailure() : storageFailure();
            throw aborted ? safe : withAbortFailure(safe);
        }
    }

    private ArrayList<MinioMultipartOperations.UploadedPart> uploadParts(
            String bucket, String key, String uploadId, InputStream stream, long length) throws Exception {
        var parts = new ArrayList<MinioMultipartOperations.UploadedPart>();
        long remaining = length;
        int partNumber = 1;
        while (remaining > 0) {
            int expected = (int) Math.min(PART_SIZE_BYTES, remaining);
            byte[] bytes = new byte[expected];
            readFully(stream, bytes, expected);
            parts.add(operations.uploadPart(bucket, key, uploadId, partNumber++, bytes, expected));
            remaining -= expected;
        }
        if (stream.read() != -1) throw new IOException("declared length is shorter than stream");
        return parts;
    }

    private static void readFully(InputStream stream, byte[] bytes, int expected) throws IOException {
        int offset = 0;
        while (offset < expected) {
            int count = stream.read(bytes, offset, expected - offset);
            if (count < 0) throw new IOException("declared length is longer than stream");
            if (count == 0) {
                int value = stream.read();
                if (value < 0) throw new IOException("declared length is longer than stream");
                bytes[offset++] = (byte) value;
            } else {
                offset += count;
            }
        }
    }

    private boolean verifyExisting(
            String bucket, String key, long length, String contentType, String digest) {
        final MinioMultipartOperations.StoredObject existing;
        try {
            existing = operations.stat(bucket, key);
        } catch (Exception failure) {
            throw storageFailure();
        }
        if (existing.length() != length || !contentType.equals(existing.contentType())
                || !digest.equals(existing.sha256())) {
            throw conflict();
        }
        return false;
    }

    private boolean abortQuietly(String bucket, String key, String uploadId) {
        if (uploadId == null) return true;
        try {
            operations.abort(bucket, key, uploadId);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isConditionalConflict(ErrorResponseException failure) {
        String code = failure.errorResponse() == null ? null : failure.errorResponse().code();
        int status = failure.response() == null ? -1 : failure.response().code();
        return (status == 412 && "PreconditionFailed".equals(code))
                || (status == 409 && "ConditionalRequestConflict".equals(code));
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

    private static IllegalStateException uncertainWriteFailure() {
        return new IllegalStateException("MINIO_OBJECT_WRITE_UNCERTAIN", null);
    }

    private static IllegalStateException withAbortFailure(IllegalStateException failure) {
        failure.addSuppressed(new IllegalStateException("MINIO_MULTIPART_ABORT_FAILED", null));
        return failure;
    }
}
