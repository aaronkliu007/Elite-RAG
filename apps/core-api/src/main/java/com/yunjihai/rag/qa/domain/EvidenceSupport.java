package com.yunjihai.rag.qa.domain;

import java.util.Objects;
import java.util.UUID;

public record EvidenceSupport(UUID evidenceId, String quote) {
    public EvidenceSupport {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(quote, "quote must not be null");
        if (quote.isBlank() || quote.codePointCount(0, quote.length()) > 1_200) {
            throw new IllegalArgumentException("evidence quote length is invalid");
        }
    }
}
