package com.yunjihai.rag.access;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record PrincipalMembershipSnapshot(
        Set<UUID> departmentIds,
        Map<UUID, UUID> parentDepartmentByChild,
        Set<UUID> roleIds,
        Set<UUID> projectGroupIds) {

    public PrincipalMembershipSnapshot {
        departmentIds = Set.copyOf(Objects.requireNonNull(departmentIds));
        parentDepartmentByChild = Map.copyOf(Objects.requireNonNull(parentDepartmentByChild));
        roleIds = Set.copyOf(Objects.requireNonNull(roleIds));
        projectGroupIds = Set.copyOf(Objects.requireNonNull(projectGroupIds));
    }
}
