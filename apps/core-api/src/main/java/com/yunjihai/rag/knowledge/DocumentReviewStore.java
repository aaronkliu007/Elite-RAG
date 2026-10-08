package com.yunjihai.rag.knowledge;

import java.util.List;
import java.util.UUID;

public interface DocumentReviewStore {

    void append(DocumentReview review);

    List<DocumentReview> listByDocumentId(UUID documentId);

    static DocumentReviewStore noop() {
        return new DocumentReviewStore() {
            @Override
            public void append(DocumentReview review) { }

            @Override
            public List<DocumentReview> listByDocumentId(UUID documentId) {
                return List.of();
            }
        };
    }
}
