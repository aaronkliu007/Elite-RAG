package com.yunjihai.rag.knowledge;

import java.util.Objects;
import java.util.UUID;

public record DocumentVersionRef(UUID documentId, UUID versionId) {

    public DocumentVersionRef {
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(versionId);
    }
}
