package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.knowledge.DocumentReview;
import com.yunjihai.rag.knowledge.DocumentReviewStore;
import com.yunjihai.rag.knowledge.ReviewDecision;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class JdbcDocumentReviewStore implements DocumentReviewStore {
    private final JdbcClient jdbc;

    public JdbcDocumentReviewStore(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    @Transactional
    public void append(DocumentReview review) {
        Objects.requireNonNull(review);
        try {
            jdbc.sql("""
                    INSERT INTO document_review (
                        id, document_id, version_id, reviewer_id, decision, comment, reviewed_at)
                    VALUES (
                        :id, :documentId, :versionId, :reviewerId, :decision, :comment, :reviewedAt)
                    """)
                    .param("id", review.id())
                    .param("documentId", review.documentId())
                    .param("versionId", review.versionId())
                    .param("reviewerId", review.reviewerId())
                    .param("decision", review.decision().name())
                    .param("comment", review.comment())
                    .param("reviewedAt", OffsetDateTime.ofInstant(review.reviewedAt(), ZoneOffset.UTC))
                    .update();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public List<DocumentReview> listByDocumentId(UUID documentId) {
        Objects.requireNonNull(documentId);
        try {
            return jdbc.sql("""
                    SELECT id, document_id, version_id, reviewer_id, decision, comment, reviewed_at
                    FROM document_review
                    WHERE document_id = :documentId
                    ORDER BY reviewed_at, id
                    """)
                    .param("documentId", documentId)
                    .query((row, number) -> new DocumentReview(
                            row.getObject("id", UUID.class),
                            row.getObject("document_id", UUID.class),
                            row.getObject("version_id", UUID.class),
                            row.getObject("reviewer_id", UUID.class),
                            ReviewDecision.valueOf(row.getString("decision")),
                            row.getString("comment"),
                            row.getObject("reviewed_at", OffsetDateTime.class).toInstant()))
                    .list();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }
}
