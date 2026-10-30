package com.yunjihai.rag.solution.proposal;

import java.time.Duration;
import java.util.Optional;

public interface ProposalAttemptStore {
    ProposalRegistration register(ProposalRegistrationRequest request, Duration lease);
    Optional<ProposalAttempt> findByKey(ProposalKey key);
    Optional<ProposalAttempt> findCurrent(ProposalRegistrationRequest request);
    boolean succeed(ProposalRegistrationRequest request, String resultJson);
    boolean fail(ProposalRegistrationRequest request, String failureCode);
    int expireStale(int batchSize);
}
