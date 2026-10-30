package com.yunjihai.rag.solution.proposal;

import java.util.Objects;
import java.util.UUID;

public record ProposalKey(UUID actorId, UUID solutionId, ProposalKind kind, String clientKey) {
    public ProposalKey {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(solutionId);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(clientKey);
        clientKey = clientKey.strip();
        if (clientKey.isEmpty() || clientKey.length() > 200) {
            throw new IllegalArgumentException("invalid proposal key");
        }
    }

    @Override public String toString() {
        return "ProposalKey[actorId=" + actorId + ", solutionId=" + solutionId
                + ", kind=" + kind + "]";
    }
}
