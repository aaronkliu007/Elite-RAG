package com.yunjihai.rag.qa;

import java.time.Duration;
import java.util.Objects;

public record QaPolicy(
        int maxQuestionCodePoints,
        int maxAuthorizedSpaces,
        int requestedEvidenceCount,
        int minEvidenceCount,
        int maxEvidenceCount,
        int maxEvidenceCodePoints,
        int maxModelContextCodePoints,
        int maxModelOutputCodePoints,
        Duration totalTimeout,
        int transientRetryLimit) {

    public QaPolicy {
        if (maxQuestionCodePoints < 1 || maxQuestionCodePoints > 2_000) {
            throw new IllegalArgumentException("max question length is invalid");
        }
        if (maxAuthorizedSpaces < 1 || maxAuthorizedSpaces > 100) {
            throw new IllegalArgumentException("max authorized spaces is invalid");
        }
        if (minEvidenceCount < 1 || maxEvidenceCount < minEvidenceCount
                || maxEvidenceCount > 15
                || requestedEvidenceCount < minEvidenceCount
                || requestedEvidenceCount > maxEvidenceCount) {
            throw new IllegalArgumentException("evidence count policy is invalid");
        }
        if (maxEvidenceCodePoints < 1 || maxEvidenceCodePoints > 1_200) {
            throw new IllegalArgumentException("max evidence length is invalid");
        }
        if (maxModelContextCodePoints < maxEvidenceCodePoints
                || maxModelContextCodePoints > 12_000) {
            throw new IllegalArgumentException("model context limit is invalid");
        }
        if (maxModelOutputCodePoints < 1 || maxModelOutputCodePoints > 2_000) {
            throw new IllegalArgumentException("model output limit is invalid");
        }
        Objects.requireNonNull(totalTimeout, "totalTimeout must not be null");
        if (totalTimeout.isZero() || totalTimeout.isNegative()
                || totalTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("total timeout is invalid");
        }
        if (transientRetryLimit < 0 || transientRetryLimit > 1) {
            throw new IllegalArgumentException("transient retry limit is invalid");
        }
    }

    public static QaPolicy defaults() {
        return new QaPolicy(
                2_000,
                100,
                10,
                8,
                15,
                1_200,
                12_000,
                2_000,
                Duration.ofSeconds(30),
                1);
    }
}
