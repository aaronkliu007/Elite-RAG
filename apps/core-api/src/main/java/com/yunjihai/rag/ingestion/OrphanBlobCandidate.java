package com.yunjihai.rag.ingestion;

public record OrphanBlobCandidate(String bucket, String sha256, String objectKey) {
}
