package com.yunjihai.rag.knowledge;

import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository {

    void insert(DocumentAggregate document);

    Optional<DocumentAggregate> findById(UUID documentId);

    void replace(DocumentAggregate document, long expectedRevision);
}
