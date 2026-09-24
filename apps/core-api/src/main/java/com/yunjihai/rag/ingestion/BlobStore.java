package com.yunjihai.rag.ingestion;

import org.springframework.http.MediaType;

import java.io.InputStream;

public interface BlobStore {

    StoredBlob putIfAbsent(String sha256, InputStream stream, long length, MediaType mediaType);

    void deleteIfUnreferenced(StoredBlob blob);
}
