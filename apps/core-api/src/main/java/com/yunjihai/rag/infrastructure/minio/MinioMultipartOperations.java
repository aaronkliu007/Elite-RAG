package com.yunjihai.rag.infrastructure.minio;

import java.util.List;

interface MinioMultipartOperations {

    String begin(String bucket, String key, String contentType, String sha256) throws Exception;

    UploadedPart uploadPart(
            String bucket, String key, String uploadId, int partNumber, byte[] bytes, int length) throws Exception;

    void completeIfAbsent(
            String bucket, String key, String uploadId, List<UploadedPart> parts) throws Exception;

    void abort(String bucket, String key, String uploadId) throws Exception;

    StoredObject stat(String bucket, String key) throws Exception;

    record UploadedPart(int number, String etag) {
    }

    record StoredObject(long length, String contentType, String sha256) {
    }
}
