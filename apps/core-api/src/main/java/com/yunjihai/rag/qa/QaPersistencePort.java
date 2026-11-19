package com.yunjihai.rag.qa;

import com.yunjihai.rag.qa.domain.EvidenceBundle;
import com.yunjihai.rag.qa.domain.QaOutcome;
import com.yunjihai.rag.qa.domain.QaStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.List;
import com.yunjihai.rag.retrieval.domain.Evidence;

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

    /** Save identities only; fail closed if an implementation does not support the scoped path. */
    default boolean saveConflictScreening(UUID requestId,long expectedRevision,List<Evidence> candidates) { return false; }

    Optional<RequestState> complete(
            UUID requestId, long expectedRevision, QaOutcome outcome, Instant at);

    default AuthorizedCompletion completeAuthorized(
            UUID requestId, long expectedRevision, QaOutcome.Answered outcome, Instant at,
            BooleanSupplier authorizationCheck) {
        Objects.requireNonNull(authorizationCheck, "authorizationCheck must not be null");
        if (!authorizationCheck.getAsBoolean()) return new AuthorizedCompletion(false, Optional.empty());
        return new AuthorizedCompletion(true, complete(requestId, expectedRevision, outcome, at));
    }

    record AuthorizedCompletion(boolean authorized, Optional<RequestState> state) {
        public AuthorizedCompletion {
            state = Objects.requireNonNull(state, "state must not be null");
            if (!authorized && state.isPresent()) {
                throw new IllegalArgumentException("unauthorized completion cannot contain state");
            }
        }
    }

    Optional<QaOutcome> find(UUID requestId, UUID actorId);

    /** Audit provenance for completed answers; missing provenance cannot authorize a historical answer. */
    default Optional<String> answerPolicyVersion(UUID requestId, UUID actorId) { return Optional.empty(); }

    default Optional<QaOutcome> findAuthorized(UUID requestId, UUID actorId, BooleanSupplier authorizationCheck) {
        return find(requestId, actorId).filter(result -> !(result instanceof QaOutcome.Answered)
                || authorizationCheck.getAsBoolean());
    }

    default Optional<Instant> completedAt(UUID requestId, UUID actorId) {
        return Optional.empty();
    }
}
