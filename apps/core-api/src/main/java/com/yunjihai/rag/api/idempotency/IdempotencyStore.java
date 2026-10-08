package com.yunjihai.rag.api.idempotency;

import java.time.Instant;

public interface IdempotencyStore {
    Claim claim(IdempotencyScope scope, String requestHash, Instant now, Instant expiresAt);

    void complete(IdempotencyScope scope, String requestHash, StoredHttpResult result, Instant at);

    void release(IdempotencyScope scope, String requestHash);

    sealed interface Claim {
        record Acquired() implements Claim { }
        record InProgress() implements Claim { }
        record HashMismatch() implements Claim { }
        record Completed(StoredHttpResult result) implements Claim { }
    }
}
