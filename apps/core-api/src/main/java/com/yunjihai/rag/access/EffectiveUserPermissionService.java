package com.yunjihai.rag.access;

import com.yunjihai.rag.access.role.RoleAssignmentStore;
import com.yunjihai.rag.access.role.SpaceRole;
import com.yunjihai.rag.access.role.SystemRole;
import com.yunjihai.rag.identity.local.UserAccountStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Reports space-level access using the same principals and expiry rules as read authorization. */
public final class EffectiveUserPermissionService {
    private final PermissionGrantQuery grants;
    private final EffectivePrincipalSet principals;
    private final UserAccountStore users;
    private final RoleAssignmentStore roles;
    private final Clock clock;

    public EffectiveUserPermissionService(PermissionGrantQuery grants, EffectivePrincipalSet principals,
            UserAccountStore users, RoleAssignmentStore roles, Clock clock) {
        this.grants = Objects.requireNonNull(grants); this.principals = Objects.requireNonNull(principals);
        this.users = Objects.requireNonNull(users); this.roles = Objects.requireNonNull(roles);
        this.clock = Objects.requireNonNull(clock);
    }

    public Result forUser(UUID spaceId, UUID userId) {
        var user = users.findById(userId).orElseThrow(AccessDeniedAsNotFoundException::new);
        var effectivePrincipals = principals.forUser(userId);
        var at = clock.instant();
        var matched = new ArrayList<Source>();
        for (int offset = 0; ; offset = Math.addExact(offset, 100)) {
            var page = grants.listForSpace(spaceId, offset, 100);
            for (var grant : page) {
                var principal = new PrincipalRef(PrincipalType.valueOf(grant.principalType()), grant.principalId());
                if (!effectivePrincipals.contains(principal)) continue;
                boolean effective = user.enabled() && (grant.expiresAt() == null || grant.expiresAt().toInstant().isAfter(at));
                matched.add(new Source(grant, principal.type() != PrincipalType.USER, effective));
            }
            if (page.size() < 100) break;
        }
        return new Result(user.id(), user.username(), user.displayName(), user.enabled(),
                matched.stream().anyMatch(Source::effective),
                user.enabled() && roles.hasSystemRole(userId, SystemRole.SYSTEM_ADMIN),
                user.enabled() ? roles.activeSpaceRoles(userId, spaceId) : Set.of(), List.copyOf(matched));
    }

    public record Source(PermissionGrantQuery.Grant grant, boolean inherited, boolean effective) { }
    public record Result(UUID userId, String username, String displayName, boolean enabled,
                         boolean spaceReadable, boolean systemAdmin, Set<SpaceRole> spaceRoles, List<Source> grants) { }
}
