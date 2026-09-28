package com.yunjihai.rag.solution.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record VersionConfirmation(UUID versionId, UUID confirmedBy, Instant confirmedAt) {

    public VersionConfirmation {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(confirmedBy, "confirmedBy");
        Objects.requireNonNull(confirmedAt, "confirmedAt");
    }
}
