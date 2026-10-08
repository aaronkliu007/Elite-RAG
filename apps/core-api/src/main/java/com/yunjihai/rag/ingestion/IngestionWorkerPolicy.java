package com.yunjihai.rag.ingestion;

import java.time.Duration;
import java.util.Objects;

/** 统一约束解析调用期限与数据库租约，避免正常解析被其他 Worker 重领。 */
public record IngestionWorkerPolicy(Duration leaseDuration, int parserTimeoutSeconds) {

    private static final Duration MINIMUM_PROCESSING_OVERHEAD = Duration.ofMinutes(2);

    public IngestionWorkerPolicy {
        Objects.requireNonNull(leaseDuration);
        if (parserTimeoutSeconds < 1 || parserTimeoutSeconds > 300) {
            throw new IllegalArgumentException("解析超时参数无效");
        }
        var minimumLease = Duration.ofSeconds(parserTimeoutSeconds)
                .plus(MINIMUM_PROCESSING_OVERHEAD);
        if (leaseDuration.compareTo(minimumLease) < 0) {
            throw new IllegalArgumentException("入库租约必须长于解析超时", null);
        }
    }
}
