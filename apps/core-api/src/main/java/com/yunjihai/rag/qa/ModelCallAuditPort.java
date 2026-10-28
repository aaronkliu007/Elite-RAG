package com.yunjihai.rag.qa;

import com.yunjihai.rag.qa.model.ModelUsage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@FunctionalInterface
public interface ModelCallAuditPort {
    record ModelCallAudit(
            UUID requestId,
            String provider,
            String endpointIdentifier,
            String model,
            String modelConfigVersion,
            String requestDigest,
            String responseDigest,
            ModelUsage usage,
            int sanitizationHitCount,
            int retryCount,
            String resultCode,
            Instant occurredAt) {
        public ModelCallAudit {
            Objects.requireNonNull(requestId, "requestId must not be null");
            requireIdentifier(provider, "provider");
            requireIdentifier(endpointIdentifier, "endpointIdentifier");
            requireText(model, "model");
            if (model.codePointCount(0, model.length()) > 200) {
                throw new IllegalArgumentException("model is invalid");
            }
            requireIdentifier(modelConfigVersion, "modelConfigVersion");
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

        private static void requireIdentifier(String value, String field) {
            requireText(value, field);
            if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw new IllegalArgumentException(field + " is invalid");
            }
        }
    }

    void record(ModelCallAudit audit);
}
