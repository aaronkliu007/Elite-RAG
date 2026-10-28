package com.yunjihai.rag.solution.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface SolutionCommandIdempotencyStore {
    Optional<SolutionCommandResult> claim(Key key, String digest);
    void complete(Key key, SolutionCommandResult result);
    void release(Key key);

    record Key(UUID actorId, UUID resourceId, String action, String clientKey) {
        public Key {
            Objects.requireNonNull(actorId);
            Objects.requireNonNull(resourceId);
            Objects.requireNonNull(action);
            Objects.requireNonNull(clientKey);
            if (action.isBlank() || clientKey.isBlank() || clientKey.length() > 200)
                throw new IllegalArgumentException("invalid idempotency key");
        }
    }
}
