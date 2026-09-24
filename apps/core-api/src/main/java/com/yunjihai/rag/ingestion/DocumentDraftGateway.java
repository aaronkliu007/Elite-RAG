package com.yunjihai.rag.ingestion;

@FunctionalInterface
public interface DocumentDraftGateway {

    DocumentVersionId createDraft(ValidatedUpload command);
}
