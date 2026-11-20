package com.yunjihai.rag.access;

import java.time.Instant;
import java.util.UUID;

public interface AclCommandRepository {

    boolean isWithinSpaceScope(UUID spaceId, PrincipalRef principal, Instant at);

    void grantSpace(UUID spaceId, PrincipalRef principal, Instant validUntil, Instant at);

    default void grantSpaceRoleAccess(UUID spaceId, PrincipalRef principal, Instant at) {
        grantSpace(spaceId, principal, null, at);
    }

    void grantDocument(
            UUID spaceId,
            UUID documentId,
            PrincipalRef principal,
            Instant validUntil,
            Instant at);

    void revokeSpace(UUID spaceId, PrincipalRef principal, Instant at);

    default void revokeSpaceRoleAccess(UUID spaceId, PrincipalRef principal, Instant at) {
        revokeSpace(spaceId, principal, at);
    }

    void revokeDocument(UUID spaceId, UUID documentId, PrincipalRef principal, Instant at);
}
