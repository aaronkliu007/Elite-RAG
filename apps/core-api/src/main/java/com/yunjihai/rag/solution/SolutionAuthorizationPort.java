package com.yunjihai.rag.solution;

import java.time.Instant;
import java.util.UUID;

@FunctionalInterface
public interface SolutionAuthorizationPort {

    void require(
            UUID actorId,
            UUID spaceId,
            UUID solutionId,
            SolutionAction action,
            Instant at);

    static SolutionAuthorizationPort allowAll() {
        return (actorId, spaceId, solutionId, action, at) -> { };
    }
}
