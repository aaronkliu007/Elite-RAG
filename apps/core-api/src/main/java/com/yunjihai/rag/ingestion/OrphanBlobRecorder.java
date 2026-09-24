package com.yunjihai.rag.ingestion;

@FunctionalInterface
public interface OrphanBlobRecorder {

    void record(OrphanBlobCandidate candidate);
}
