package com.yunjihai.rag.ingestion;

@FunctionalInterface
public interface BlobCleanupEligibilityRepository {

    boolean isEligible(OrphanBlobCandidate candidate);
}
