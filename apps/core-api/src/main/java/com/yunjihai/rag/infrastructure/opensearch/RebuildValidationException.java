package com.yunjihai.rag.infrastructure.opensearch;

public final class RebuildValidationException extends RuntimeException {
    public RebuildValidationException() {
        super("OPENSEARCH_REBUILD_VALIDATION_FAILED", null, false, true);
    }
}
