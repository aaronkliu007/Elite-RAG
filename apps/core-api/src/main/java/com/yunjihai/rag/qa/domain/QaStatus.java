package com.yunjihai.rag.qa.domain;

import java.util.Objects;
import java.util.Set;

public enum QaStatus {
    RECEIVED,
    RETRIEVING,
    SANITIZING,
    GENERATING,
    VERIFYING,
    ANSWERED,
    REFUSED,
    FAILED;

    public boolean isTerminal() {
        return this == ANSWERED || this == REFUSED || this == FAILED;
    }

    public QaStatus advanceTo(QaStatus next) {
        Objects.requireNonNull(next, "next status must not be null");
        if (isTerminal() || !allowedNext().contains(next)) {
            throw new IllegalStateException("invalid qa status transition");
        }
        return next;
    }

    private Set<QaStatus> allowedNext() {
        return switch (this) {
            case RECEIVED -> Set.of(RETRIEVING, REFUSED, FAILED);
            case RETRIEVING -> Set.of(SANITIZING, REFUSED, FAILED);
            case SANITIZING -> Set.of(GENERATING, REFUSED, FAILED);
            case GENERATING -> Set.of(VERIFYING, REFUSED, FAILED);
            case VERIFYING -> Set.of(ANSWERED, REFUSED, FAILED);
            case ANSWERED, REFUSED, FAILED -> Set.of();
        };
    }
}
