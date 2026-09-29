package com.yunjihai.rag.ingestion;

import org.springframework.http.MediaType;

import java.io.InputStream;
import java.util.Objects;

public final class MinioBlobStore implements BlobStore {

    private final String bucket;
    private final MinioObjectClient client;
    private final OrphanBlobRecorder orphanRecorder;

    public MinioBlobStore(String bucket, MinioObjectClient client, OrphanBlobRecorder orphanRecorder) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("MinIO bucket 不能为空");
        }
        this.bucket = bucket;
        this.client = Objects.requireNonNull(client);
        this.orphanRecorder = Objects.requireNonNull(orphanRecorder);
    }

    @Override
    public StoredBlob putIfAbsent(
            String sha256, InputStream stream, long length, MediaType mediaType) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SHA-256 无效");
        }
        var key = "sha256/" + sha256.substring(0, 2) + "/" + sha256;
        try {
            var created = client.putIfAbsent(bucket, key, stream, length, mediaType.toString());
            return new StoredBlob(sha256, key, length, mediaType, created);
        } catch (IllegalStateException failure) {
            if (isUncertainWrite(failure)) {
                try {
                    orphanRecorder.record(new OrphanBlobCandidate(bucket, sha256, key));
                } catch (RuntimeException ignored) {
                    failure.addSuppressed(new IllegalStateException("BLOB_CLEANUP_RECORD_FAILED", null));
                }
            }
            throw failure;
        }
    }

    @Override
    public void scheduleCleanup(StoredBlob blob) {
        if (blob.created()) {
            orphanRecorder.record(new OrphanBlobCandidate(bucket, blob.sha256(), blob.objectKey()));
        }
    }

    private static boolean isUncertainWrite(IllegalStateException failure) {
        return failure.getCause() == null && "MINIO_OBJECT_WRITE_UNCERTAIN".equals(failure.getMessage());
    }
}
