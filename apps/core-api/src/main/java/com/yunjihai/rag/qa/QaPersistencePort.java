package com.yunjihai.rag.qa;

import com.yunjihai.rag.qa.domain.EvidenceBundle;
import com.yunjihai.rag.qa.domain.QaOutcome;
import com.yunjihai.rag.qa.domain.QaStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface QaPersistencePort {
    record RequestState(UUID requestId, UUID actorId, QaStatus status, long revision, boolean degraded) {
        public RequestState {
            Objects.requireNonNull(requestId, "requestId must not be null");
            Objects.requireNonNull(actorId, "actorId must not be null");
            Objects.requireNonNull(status, "status must not be null");
            if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        }
    }

    RequestState create(UUID requestId, UUID actorId, String questionDigest, Instant at);

    Optional<RequestState> transition(UUID requestId, long expectedRevision, QaStatus next, Instant at);

    Optional<RequestState> saveEvidence(
            UUID requestId, long expectedRevision, EvidenceBundle evidence, boolean degraded, Instant at);

    Optional<RequestState> complete(
            UUID requestId, long expectedRevision, QaOutcome outcome, Instant at);

    Optional<QaOutcome> find(UUID requestId, UUID actorId);
}
