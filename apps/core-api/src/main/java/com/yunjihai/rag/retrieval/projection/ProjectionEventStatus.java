package com.yunjihai.rag.retrieval.projection;

public enum ProjectionEventStatus {
    PENDING,
    LEASED,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED
}
