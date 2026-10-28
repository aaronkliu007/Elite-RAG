package com.yunjihai.rag.solution.application;

import java.util.Objects;
import java.util.UUID;

public record SolutionReviewPolicy(boolean allowSelfConfirmation) {
    public boolean canConfirm(UUID editorId, UUID reviewerId) {
        Objects.requireNonNull(editorId);
        Objects.requireNonNull(reviewerId);
        return allowSelfConfirmation || !editorId.equals(reviewerId);
    }
}
