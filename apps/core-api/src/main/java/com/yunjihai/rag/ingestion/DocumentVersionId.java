package com.yunjihai.rag.ingestion;

import java.util.UUID;

public record DocumentVersionId(UUID documentId, UUID versionId) {
}
