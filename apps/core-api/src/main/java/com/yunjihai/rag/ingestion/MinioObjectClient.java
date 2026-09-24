package com.yunjihai.rag.ingestion;

import java.io.InputStream;

public interface MinioObjectClient {

    boolean putIfAbsent(String bucket, String key, InputStream stream, long length, String contentType);

    void deleteIfUnreferenced(String bucket, String key);
}
