package com.yunjihai.rag.ingestion;

import java.util.Objects;

public final class IngestionWorker {

    private final IngestionJobService jobs;
    private final ParserInvocation parser;

    public IngestionWorker(IngestionJobService jobs, ParserInvocation parser) {
        this.jobs = Objects.requireNonNull(jobs);
        this.parser = Objects.requireNonNull(parser);
    }

    public boolean runOnce(String workerId) {
        var lease = jobs.claimNext(workerId);
        if (lease.isEmpty()) {
            return false;
        }
        var claimed = lease.orElseThrow();
        CanonicalDocumentV1 result;
        try {
            result = parser.parse(claimed);
        } catch (RuntimeException failure) {
            jobs.fail(claimed, new ParserError(
                    ParserErrorCode.CONVERSION_FAILED,
                    "文档入库失败",
                    true));
            return true;
        }
        if (result != null
                && result.status() == ParserStatus.FAILED
                && result.errors() != null
                && !result.errors().isEmpty()) {
            jobs.fail(claimed, result.errors().getFirst());
        } else {
            jobs.complete(claimed, result);
        }
        return true;
    }
}
