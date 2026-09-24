package com.yunjihai.rag.ingestion;

import java.util.List;
import java.util.UUID;

public record CanonicalDocumentV1(
        String schemaVersion,
        UUID jobId,
        ParserStatus status,
        CanonicalDocumentBody document,
        List<ParserError> errors) {
}
