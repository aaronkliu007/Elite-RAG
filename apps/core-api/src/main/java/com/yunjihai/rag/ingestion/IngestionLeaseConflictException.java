package com.yunjihai.rag.ingestion;

/** Stable domain failure returned when a worker no longer owns an active lease. */
public final class IngestionLeaseConflictException extends IllegalStateException {
    public IngestionLeaseConflictException() {
        super("INGESTION_LEASE_CONFLICT", null);
    }
}
