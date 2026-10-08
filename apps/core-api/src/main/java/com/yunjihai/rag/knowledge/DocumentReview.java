package com.yunjihai.rag.knowledge;

import com.yunjihai.rag.common.UuidV7;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DocumentReview(
        UUID id,
        UUID documentId,
        UUID versionId,
        UUID reviewerId,
        ReviewDecision decision,
        String comment,
        Instant reviewedAt) {

    public DocumentReview {
        Objects.requireNonNull(id);
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(versionId);
        Objects.requireNonNull(reviewerId);
        Objects.requireNonNull(decision);
        Objects.requireNonNull(reviewedAt);
        if (comment != null) {
            comment = comment.strip();
            if (comment.isEmpty() || codePoints(comment) > 1000) {
                throw new IllegalArgumentException("审核意见必须为 1 至 1000 个字符");
            }
        }
        if (decision == ReviewDecision.REJECTED && comment == null) {
            throw new IllegalArgumentException("驳回必须填写审核意见");
        }
    }

    public static DocumentReview rejected(
            UUID documentId, UUID versionId, UUID reviewerId, String comment, Instant at) {
        return new DocumentReview(
                UuidV7.generate(at), documentId, versionId, reviewerId,
                ReviewDecision.REJECTED, comment, at);
    }

    public static DocumentReview published(
            UUID documentId, UUID versionId, UUID reviewerId, Instant at) {
        return new DocumentReview(
                UuidV7.generate(at), documentId, versionId, reviewerId,
                ReviewDecision.PUBLISHED, null, at);
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }
}
