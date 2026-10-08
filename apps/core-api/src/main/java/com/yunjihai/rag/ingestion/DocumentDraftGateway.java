package com.yunjihai.rag.ingestion;

import java.util.Objects;

@FunctionalInterface
public interface DocumentDraftGateway {

    DraftResult createDraft(ValidatedUpload command);

    record DraftResult(DocumentVersionId version, boolean created) {
        public DraftResult {
            Objects.requireNonNull(version);
        }
    }
}
