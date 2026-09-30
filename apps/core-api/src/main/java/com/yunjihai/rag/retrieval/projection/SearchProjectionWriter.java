package com.yunjihai.rag.retrieval.projection;

public interface SearchProjectionWriter {
    void apply(String targetIndex, RetrievalProjectionSnapshot snapshot);
}
