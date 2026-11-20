package com.yunjihai.rag.access;

import com.yunjihai.rag.access.role.RoleAssignmentStore;
import com.yunjihai.rag.access.role.SpaceRole;
import com.yunjihai.rag.access.role.SystemRole;
import com.yunjihai.rag.identity.local.UserAccount;
import com.yunjihai.rag.identity.local.UserAccountStore;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class EffectiveUserPermissionServiceTest {
    @Test void includesParentDepartmentAndDirectGrantButExcludesUnrelatedAndExpiredFromAccess() {
        UUID user = UUID.randomUUID(), dept = UUID.randomUUID(), parent = UUID.randomUUID(), other = UUID.randomUUID(), space = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-20T00:00:00Z");
        var account = new UserAccount(user, "zhang", "张三", true, 0, now, now);
        var principals = new EffectivePrincipalSet(id -> new PrincipalMembershipSnapshot(Set.of(dept), Map.of(dept, parent), Set.of(), Set.of()));
        var records = List.of(grant("USER", user, now.minusSeconds(1)), grant("DEPARTMENT", parent, now.plusSeconds(60)), grant("DEPARTMENT", other, null));
        PermissionGrantQuery query = (id, offset, limit) -> offset == 0 ? records : List.of();
        var service = new EffectiveUserPermissionService(query, principals, users(account), roles(), Clock.fixed(now, ZoneOffset.UTC));
        var result = service.forUser(space, user);
        assertThat(result.spaceReadable()).isTrue();
        assertThat(result.grants()).hasSize(2);
        assertThat(result.grants()).anySatisfy(g -> { assertThat(g.inherited()).isTrue(); assertThat(g.effective()).isTrue(); });
        assertThat(result.grants()).anySatisfy(g -> { assertThat(g.inherited()).isFalse(); assertThat(g.effective()).isFalse(); });
        var disabled = new UserAccount(user, "zhang", "张三", false, 0, now, now);
        var disabledResult = new EffectiveUserPermissionService(query, principals, users(disabled), roles(), Clock.fixed(now, ZoneOffset.UTC)).forUser(space, user);
        assertThat(disabledResult.spaceReadable()).isFalse();
        assertThat(disabledResult.grants()).allSatisfy(g -> assertThat(g.effective()).isFalse());
    }
    private static PermissionGrantQuery.Grant grant(String type, UUID id, Instant expires) {
        return new PermissionGrantQuery.Grant(UUID.randomUUID(), type, id, null, null, "DIRECT", expires == null ? null : expires.atOffset(ZoneOffset.UTC));
    }
    private static UserAccountStore users(UserAccount account) { return new UserAccountStore() {
        public void insert(UserAccount a, String p, String s) { }
        public Optional<UserAccount> findById(UUID id) { return Optional.of(account); }
        public List<UserAccount> list() { return List.of(account); }
        public void setEnabled(UUID id, boolean enabled, Instant at) { }
    }; }
    private static RoleAssignmentStore roles() { return new RoleAssignmentStore() {
        public boolean hasSystemRole(UUID id, SystemRole role) { return false; }
        public boolean hasAnySystemRole(SystemRole role) { return false; }
        public Set<SpaceRole> activeSpaceRoles(UUID id, UUID space) { return Set.of(); }
        public void grantSystemRole(UUID id, SystemRole role, UUID actor, Instant at) { }
        public void grantSpaceRole(UUID space, UUID id, SpaceRole role, UUID actor, Instant at) { }
        public void revokeSpaceRole(UUID space, UUID id, SpaceRole role, UUID actor, Instant at) { }
    }; }
}
