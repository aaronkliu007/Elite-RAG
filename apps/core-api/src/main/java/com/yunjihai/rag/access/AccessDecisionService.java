package com.yunjihai.rag.access;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class AccessDecisionService {

    private final EffectivePrincipalSet effectivePrincipalSet;
    private final AccessDecisionRepository accessDecisionRepository;

    public AccessDecisionService(
            EffectivePrincipalSet effectivePrincipalSet,
            AccessDecisionRepository accessDecisionRepository) {
        this.effectivePrincipalSet = Objects.requireNonNull(effectivePrincipalSet);
        this.accessDecisionRepository = Objects.requireNonNull(accessDecisionRepository);
    }

    public boolean canRead(UUID userId, UUID spaceId, UUID documentId, Instant at) {
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(at, "at must not be null");
        Set<PrincipalRef> principals = effectivePrincipalSet.forUser(userId);
        return accessDecisionRepository
                .findReadDecision(spaceId, documentId, principals, at)
                .map(snapshot -> snapshot.isReadableAt(at))
                .orElse(false);
    }

    public void requireRead(UUID userId, UUID spaceId, UUID documentId, Instant at) {
        if (!canRead(userId, spaceId, documentId, at)) {
            throw new AccessDeniedAsNotFoundException();
        }
    }
}
