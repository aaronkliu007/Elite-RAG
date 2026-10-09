package com.yunjihai.rag.ingestion;

/** Stable conflict when content deduplication would discard different business metadata. */
public final class UploadConflictException extends RuntimeException {
    public UploadConflictException() {
        super("UPLOAD_METADATA_CONFLICT", null, false, false);
    }
}
