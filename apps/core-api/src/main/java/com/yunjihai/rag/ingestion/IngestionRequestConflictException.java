package com.yunjihai.rag.ingestion;

/** Stable failure for a job request that does not match its immutable document version. */
public final class IngestionRequestConflictException extends IllegalStateException {
    public IngestionRequestConflictException() {
        super("INGESTION_REQUEST_CONFLICT", null);
    }
}
