package com.yunjihai.rag.access.role;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public interface RoleAssignmentStore {
    boolean hasSystemRole(UUID userId, SystemRole role);

    boolean hasAnySystemRole(SystemRole role);

    Set<SpaceRole> activeSpaceRoles(UUID userId, UUID spaceId);

    void grantSystemRole(UUID userId, SystemRole role, UUID actorId, Instant at);

    void grantSpaceRole(UUID spaceId, UUID userId, SpaceRole role, UUID actorId, Instant at);

    void revokeSpaceRole(UUID spaceId, UUID userId, SpaceRole role, UUID actorId, Instant at);
}
