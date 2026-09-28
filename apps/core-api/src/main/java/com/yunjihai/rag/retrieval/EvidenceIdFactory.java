package com.yunjihai.rag.retrieval;

import java.time.Instant;
import java.util.UUID;

@FunctionalInterface
public interface EvidenceIdFactory {

    UUID next(Instant at);
}
