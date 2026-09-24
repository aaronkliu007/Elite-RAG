package com.yunjihai.rag.ingestion;

import org.springframework.http.MediaType;

import java.io.InputStream;
import java.util.Objects;

public final class MinioBlobStore implements BlobStore {

    private final String bucket;
    private final MinioObjectClient client;

    public MinioBlobStore(String bucket, MinioObjectClient client) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("MinIO bucket 不能为空");
        }
        this.bucket = bucket;
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public StoredBlob putIfAbsent(
            String sha256, InputStream stream, long length, MediaType mediaType) {
        if (sha256 == null || sha256.length() < 2) {
            throw new IllegalArgumentException("SHA-256 无效");
        }
        var key = "sha256/" + sha256.substring(0, 2) + "/" + sha256;
        var created = client.putIfAbsent(bucket, key, stream, length, mediaType.toString());
        return new StoredBlob(sha256, key, length, mediaType, created);
    }

    @Override
    public void deleteIfUnreferenced(StoredBlob blob) {
        if (blob.created()) {
            client.deleteIfUnreferenced(bucket, blob.objectKey());
        }
    }
}
