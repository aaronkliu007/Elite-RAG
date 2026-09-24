package com.yunjihai.rag.ingestion;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IngestionLease(
        UUID jobId,
        UUID leaseToken,
        int attempt,
        String workerId,
        Instant leaseUntil,
        IngestionRequest request) {

    public IngestionLease {
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(leaseToken);
        Objects.requireNonNull(leaseUntil);
        Objects.requireNonNull(request);
        if (attempt < 1 || workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("入库租约无效");
        }
    }

    public ParserJob parserJob() {
        return new ParserJob(
                "parser-job-v1",
                jobId,
                request.filename(),
                request.mediaType(),
                request.sizeBytes(),
                request.sha256(),
                300);
    }
}
