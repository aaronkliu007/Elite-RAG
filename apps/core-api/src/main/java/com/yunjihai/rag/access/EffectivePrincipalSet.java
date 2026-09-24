package com.yunjihai.rag.access;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class EffectivePrincipalSet {

    private final PrincipalMembershipRepository membershipRepository;

    public EffectivePrincipalSet(PrincipalMembershipRepository membershipRepository) {
        this.membershipRepository = Objects.requireNonNull(membershipRepository);
    }

    public Set<PrincipalRef> forUser(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        PrincipalMembershipSnapshot snapshot = Objects.requireNonNull(
                membershipRepository.findByUserId(userId),
                "membership snapshot must not be null");

        Set<PrincipalRef> principals = new LinkedHashSet<>();
        principals.add(new PrincipalRef(PrincipalType.USER, userId));
        snapshot.departmentIds().forEach(departmentId ->
                addDepartmentAndAncestors(departmentId, snapshot, principals));
        snapshot.roleIds().forEach(roleId ->
                principals.add(new PrincipalRef(PrincipalType.ROLE, roleId)));
        snapshot.projectGroupIds().forEach(projectGroupId ->
                principals.add(new PrincipalRef(PrincipalType.PROJECT_GROUP, projectGroupId)));
        return Set.copyOf(principals);
    }

    private void addDepartmentAndAncestors(
            UUID departmentId,
            PrincipalMembershipSnapshot snapshot,
            Set<PrincipalRef> principals) {
        Set<UUID> visited = new HashSet<>();
        UUID current = departmentId;
        while (current != null && visited.add(current)) {
            principals.add(new PrincipalRef(PrincipalType.DEPARTMENT, current));
            current = snapshot.parentDepartmentByChild().get(current);
        }
    }
}
