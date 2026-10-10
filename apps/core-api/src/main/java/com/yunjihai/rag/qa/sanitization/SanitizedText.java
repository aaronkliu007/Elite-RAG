package com.yunjihai.rag.qa.sanitization;

import java.util.Map;
import java.util.Objects;

public record SanitizedText(String value, Map<String, Integer> hitCounts, boolean blocked) {
    public SanitizedText {
        Objects.requireNonNull(value, "sanitized value must not be null");
        hitCounts = Map.copyOf(Objects.requireNonNull(hitCounts, "hitCounts must not be null"));
        if (hitCounts.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue() < 0)) {
            throw new IllegalArgumentException("hit counts are invalid");
        }
    }
}
