package com.yunjihai.rag.ingestion;

public enum IngestionState {
    QUEUED,
    LEASED,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED
}
