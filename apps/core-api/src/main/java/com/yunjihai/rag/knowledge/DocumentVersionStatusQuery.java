package com.yunjihai.rag.knowledge;

import com.yunjihai.rag.ingestion.DocumentVersionId;

@FunctionalInterface
public interface DocumentVersionStatusQuery {
    DocumentStatus find(DocumentVersionId version);
}
