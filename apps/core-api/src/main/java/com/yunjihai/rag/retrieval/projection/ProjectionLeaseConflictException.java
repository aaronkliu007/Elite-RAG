package com.yunjihai.rag.retrieval.projection;

public final class ProjectionLeaseConflictException extends RuntimeException {
    public ProjectionLeaseConflictException() {
        super("PROJECTION_LEASE_CONFLICT");
    }
}
