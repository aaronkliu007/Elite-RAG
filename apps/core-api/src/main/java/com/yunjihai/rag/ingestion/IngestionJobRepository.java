package com.yunjihai.rag.ingestion;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IngestionJobRepository {

    void enqueue(UUID jobId, IngestionRequest request, Instant at);

    /**
     * 原子领取最早的排队任务或租约已过期的任务。持久化实现必须使用
     * {@code FOR UPDATE SKIP LOCKED}，同时递增尝试次数并写入新租约。
     */
    Optional<IngestionLease> claimNext(String workerId, Instant now, Instant leaseUntil);

    void complete(UUID jobId, UUID leaseToken, Instant at);

    void fail(UUID jobId, UUID leaseToken, ParserError error, Instant at);
}
