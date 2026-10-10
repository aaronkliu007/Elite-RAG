package com.yunjihai.rag.qa;

import com.yunjihai.rag.qa.model.ModelUsage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@FunctionalInterface
public interface ModelCallAuditPort {
    record ModelCallAudit(
            UUID requestId,
            String requestDigest,
            String responseDigest,
            ModelUsage usage,
            int sanitizationHitCount,
            int retryCount,
            String resultCode,
            Instant occurredAt) {
        public ModelCallAudit {
            Objects.requireNonNull(requestId, "requestId must not be null");
            requireText(requestDigest, "requestDigest");
            requireText(responseDigest, "responseDigest");
            Objects.requireNonNull(usage, "usage must not be null");
            if (sanitizationHitCount < 0 || retryCount < 0) {
                throw new IllegalArgumentException("audit counters must not be negative");
            }
            requireText(resultCode, "resultCode");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        }

        private static void requireText(String value, String field) {
            Objects.requireNonNull(value, field + " must not be null");
            if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    void record(ModelCallAudit audit);
}
