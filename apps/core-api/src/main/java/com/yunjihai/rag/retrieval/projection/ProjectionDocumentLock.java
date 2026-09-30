package com.yunjihai.rag.retrieval.projection;

import java.util.UUID;
import java.util.function.Supplier;

public interface ProjectionDocumentLock {
    <T> T withDocumentLock(UUID documentId, Supplier<T> action);
}
