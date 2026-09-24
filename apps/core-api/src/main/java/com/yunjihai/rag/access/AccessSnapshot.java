package com.yunjihai.rag.access;

import java.time.Instant;
import java.util.Objects;

public record AccessSnapshot(
        boolean spaceGranted,
        boolean documentRestricted,
        boolean documentGranted,
        PublicationState publicationState,
        Instant validUntil) {

    public AccessSnapshot {
        Objects.requireNonNull(publicationState, "publicationState must not be null");
    }

    public boolean isReadableAt(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (!spaceGranted || publicationState != PublicationState.PUBLISHED) {
            return false;
        }
        if (validUntil != null && !at.isBefore(validUntil)) {
            return false;
        }
        return !documentRestricted || documentGranted;
    }
}
