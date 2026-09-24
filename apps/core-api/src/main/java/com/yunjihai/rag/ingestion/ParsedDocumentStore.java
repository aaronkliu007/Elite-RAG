package com.yunjihai.rag.ingestion;

import java.time.Instant;

@FunctionalInterface
public interface ParsedDocumentStore {

    void replace(DocumentVersionId version, CanonicalDocumentBody document, Instant at);
}
