package com.yunjihai.rag.knowledge.web;

import com.yunjihai.rag.access.AclCommandRepository;
import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.access.role.RbacKnowledgeAuthorization;
import com.yunjihai.rag.access.role.RoleAssignmentService;
import com.yunjihai.rag.access.role.RoleAssignmentStore;
import com.yunjihai.rag.access.role.SpaceRole;
import com.yunjihai.rag.access.role.SystemRole;
import com.yunjihai.rag.api.idempotency.IdempotencyConflictException;
import com.yunjihai.rag.api.idempotency.IdempotencyScope;
import com.yunjihai.rag.api.idempotency.IdempotencyService;
import com.yunjihai.rag.api.idempotency.IdempotencyStore;
import com.yunjihai.rag.api.idempotency.StoredHttpResult;
import com.yunjihai.rag.identity.InMemorySessionVersionStore;
import com.yunjihai.rag.identity.SessionRevocationService;
import com.yunjihai.rag.identity.local.LocalCredential;
import com.yunjihai.rag.identity.local.LocalCredentialStore;
import com.yunjihai.rag.identity.local.PasswordHasher;
import com.yunjihai.rag.identity.local.UserAccount;
import com.yunjihai.rag.identity.local.UserAccountStore;
import com.yunjihai.rag.identity.local.UserAdministrationService;
import com.yunjihai.rag.knowledge.KnowledgeSpace;
import com.yunjihai.rag.knowledge.KnowledgeSpaceRepository;
import com.yunjihai.rag.knowledge.KnowledgeSpaceService;
import com.yunjihai.rag.retrieval.projection.ProjectionChangePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeSpaceControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final UUID ADMIN = id(1);
    private static final UUID SPACE_ADMIN = id(2);
    private static final UUID EDITOR = id(3);
    private static final UUID NEW_MEMBER = id(4);
    private static final UUID SPACE_ID = id(100);

    @Test
    void listsOnlyVisibleSpacesWithRoleSpecificAllowedActionsAndCreatesIdempotently() {
        var fixture = fixture();

        var adminView = fixture.controller.create(authentication(ADMIN), "create-space",
                new KnowledgeSpaceController.CreateSpaceRequest("研发知识库", "研发规范与项目资产"));
        var replay = fixture.controller.create(authentication(ADMIN), "create-space",
                new KnowledgeSpaceController.CreateSpaceRequest("研发知识库", "研发规范与项目资产"));

        assertThat(replay).isEqualTo(adminView);
        assertThat(fixture.spaces.values).hasSize(2);
        assertThat(adminView.description()).isEqualTo("研发规范与项目资产");
        assertThat(adminView.allowedActions()).contains("MANAGE_ACL", "CREATE_DOCUMENT", "EDIT_SPACE");
        assertThat(fixture.roleStore.activeSpaceRoles(ADMIN, adminView.id()))
                .containsExactly(SpaceRole.SPACE_ADMIN);

        assertThat(fixture.controller.list(authentication(SPACE_ADMIN), 1, 100))
                .singleElement()
                .satisfies(view -> assertThat(view.allowedActions()).contains("MANAGE_ACL", "EDIT_SPACE"));
        assertThat(fixture.controller.list(authentication(EDITOR), 1, 100))
                .singleElement()
                .satisfies(view -> assertThat(view.allowedActions())
                        .contains("CREATE_DOCUMENT", "SUBMIT_FOR_REVIEW")
                        .doesNotContain("MANAGE_ACL"));
        assertThat(fixture.controller.list(authentication(NEW_MEMBER), 1, 100)).isEmpty();
        assertThatThrownBy(() -> fixture.controller.create(authentication(ADMIN), "create-space",
                new KnowledgeSpaceController.CreateSpaceRequest("不同空间", "不同输入")))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void onlySystemOrSpaceAdminCanListGrantAndRevokeMembers() {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.controller.listMembers(authentication(EDITOR), SPACE_ID, 1, 100))
                .isInstanceOf(com.yunjihai.rag.access.AccessDeniedAsNotFoundException.class);

        var granted = fixture.controller.grantMember(
                authentication(SPACE_ADMIN), SPACE_ID, "grant-reader",
                new KnowledgeSpaceController.MemberRoleRequest(NEW_MEMBER, SpaceRole.READER));
        assertThat(granted.roles()).containsExactly(SpaceRole.READER);
        assertThat(fixture.controller.grantMember(
                authentication(SPACE_ADMIN), SPACE_ID, "grant-reader",
                new KnowledgeSpaceController.MemberRoleRequest(NEW_MEMBER, SpaceRole.READER))).isEqualTo(granted);
        assertThatThrownBy(() -> fixture.controller.grantMember(
                authentication(SPACE_ADMIN), SPACE_ID, "grant-reader",
                new KnowledgeSpaceController.MemberRoleRequest(NEW_MEMBER, SpaceRole.EDITOR)))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(fixture.controller.listMembers(authentication(SPACE_ADMIN), SPACE_ID, 1, 100))
                .extracting(KnowledgeSpaceController.MemberView::userId)
                .contains(SPACE_ADMIN, EDITOR, NEW_MEMBER);
        var revoked = fixture.controller.revokeMember(
                authentication(ADMIN), SPACE_ID, NEW_MEMBER, SpaceRole.READER, "revoke-reader");
        assertThat(revoked.roles()).isEmpty();
    }

    @Test
    void allListPaginationIsLimitedToOneThroughOneHundred() {
        var fixture = fixture();
        assertThatThrownBy(() -> fixture.controller.list(authentication(ADMIN), 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.controller.listMembers(authentication(ADMIN), SPACE_ID, 1, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullSpaceAndMemberRequestsAsBadInput() {
        var fixture = fixture();
        var admin = authentication(ADMIN);

        assertThatThrownBy(() -> fixture.controller.create(admin, "space-null", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.controller.grantMember(admin, SPACE_ID, "member-null", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.controller.grantMember(
                admin, SPACE_ID, "member-user-null",
                new KnowledgeSpaceController.MemberRoleRequest(null, SpaceRole.READER)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.controller.grantMember(
                admin, SPACE_ID, "member-role-null",
                new KnowledgeSpaceController.MemberRoleRequest(NEW_MEMBER, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Fixture fixture() {
        var roleStore = new Roles();
        roleStore.admins.add(ADMIN);
        roleStore.grantSpaceRole(SPACE_ID, SPACE_ADMIN, SpaceRole.SPACE_ADMIN, ADMIN, NOW);
        roleStore.grantSpaceRole(SPACE_ID, EDITOR, SpaceRole.EDITOR, ADMIN, NOW);
        var authorization = new RbacKnowledgeAuthorization(roleStore);
        var roles = new RoleAssignmentService(
                roleStore, noopAcl(), command -> null, ProjectionChangePublisher.noop(), authorization);
        var spaces = new Spaces(roleStore);
        spaces.insert(new KnowledgeSpace(SPACE_ID, "既有空间", "说明", 0, ADMIN, NOW));
        var spaceService = new KnowledgeSpaceService(
                spaces,
                authorization,
                (spaceId, creatorId, at) -> roles.grantSpaceRole(
                        creatorId, spaceId, creatorId, SpaceRole.SPACE_ADMIN, at));
        var accounts = new Accounts();
        accounts.add(ADMIN, "admin", "系统管理员");
        accounts.add(SPACE_ADMIN, "space-admin", "空间管理员");
        accounts.add(EDITOR, "editor", "编辑者");
        accounts.add(NEW_MEMBER, "reader", "新成员");
        var users = new UserAdministrationService(
                accounts, new Credentials(), new PlainHasher(),
                new SessionRevocationService(new InMemorySessionVersionStore()), command -> null,
                Clock.fixed(NOW, ZoneOffset.UTC));
        var json = new ObjectMapper();
        var idempotency = new IdempotencyService(
                new MemoryIdempotencyStore(), json, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(24));
        var controller = new KnowledgeSpaceController(
                spaceService, roles, users, authorization, idempotency, json,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(controller, spaces, roleStore);
    }

    private static UsernamePasswordAuthenticationToken authentication(UUID userId) {
        return new UsernamePasswordAuthenticationToken(userId.toString(), "n/a", List.of());
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-7000-8000-%012d".formatted(suffix));
    }

    private static AclCommandRepository noopAcl() {
        return new AclCommandRepository() {
            public boolean isWithinSpaceScope(UUID s, PrincipalRef p, Instant at) { return false; }
            public void grantSpace(UUID s, PrincipalRef p, Instant until, Instant at) { }
            public void grantDocument(UUID s, UUID d, PrincipalRef p, Instant until, Instant at) { }
            public void revokeSpace(UUID s, PrincipalRef p, Instant at) { }
            public void revokeDocument(UUID s, UUID d, PrincipalRef p, Instant at) { }
        };
    }

    private record Fixture(KnowledgeSpaceController controller, Spaces spaces, Roles roleStore) { }

    private static final class Spaces implements KnowledgeSpaceRepository {
        private final Map<UUID, KnowledgeSpace> values = new LinkedHashMap<>();
        private final Roles roles;
        private Spaces(Roles roles) { this.roles = roles; }
        public void insert(KnowledgeSpace space) { values.put(space.id(), space); }
        public Optional<KnowledgeSpace> findById(UUID id) { return Optional.ofNullable(values.get(id)); }
        public List<KnowledgeSpace> listAuthorized(UUID user, int offset, int limit) {
            var visible = values.values().stream()
                    .filter(space -> roles.admins.contains(user)
                            || !roles.activeSpaceRoles(user, space.id()).isEmpty())
                    .sorted(java.util.Comparator.comparing(KnowledgeSpace::createdAt).reversed())
                    .toList();
            return visible.stream().skip(offset).limit(limit).toList();
        }
        public boolean replace(long expected, KnowledgeSpace space) {
            var current = values.get(space.id());
            if (current == null || current.revision() != expected) return false;
            values.put(space.id(), space); return true;
        }
    }

    private static final class Roles implements RoleAssignmentStore {
        private final Set<UUID> admins = new java.util.HashSet<>();
        private final Map<String, Set<SpaceRole>> spaceRoles = new HashMap<>();
        public boolean hasSystemRole(UUID user, SystemRole role) { return admins.contains(user); }
        public boolean hasAnySystemRole(SystemRole role) { return !admins.isEmpty(); }
        public Set<SpaceRole> activeSpaceRoles(UUID user, UUID space) {
            return Set.copyOf(spaceRoles.getOrDefault(space + "/" + user, Set.of()));
        }
        public void grantSystemRole(UUID u, SystemRole r, UUID a, Instant at) { admins.add(u); }
        public void grantSpaceRole(UUID s, UUID u, SpaceRole r, UUID a, Instant at) {
            spaceRoles.computeIfAbsent(s + "/" + u, ignored -> new java.util.HashSet<>()).add(r);
        }
        public void revokeSpaceRole(UUID s, UUID u, SpaceRole r, UUID a, Instant at) {
            spaceRoles.getOrDefault(s + "/" + u, Set.of()).remove(r);
        }
    }

    private static final class Accounts implements UserAccountStore {
        private final Map<UUID, UserAccount> values = new HashMap<>();
        void add(UUID id, String username, String displayName) {
            values.put(id, new UserAccount(id, username, displayName, true, 0, NOW, NOW));
        }
        public void insert(UserAccount account, String p, String s) { values.put(account.id(), account); }
        public Optional<UserAccount> findById(UUID id) { return Optional.ofNullable(values.get(id)); }
        public List<UserAccount> list() { return values.values().stream().sorted(java.util.Comparator.comparing(UserAccount::username)).toList(); }
        public void setEnabled(UUID id, boolean enabled, Instant at) { }
    }

    private static final class Credentials implements LocalCredentialStore {
        public Optional<LocalCredential> lockForAuthentication(String u) { return Optional.empty(); }
        public void insert(LocalCredential c) { }
        public void updateAuthenticationState(UUID u, int f, Instant w, Instant l, Instant at) { }
        public void resetAuthenticationState(UUID u, Instant at) { }
        public void replacePassword(UUID u, String h, Instant at) { }
    }

    private static final class PlainHasher implements PasswordHasher {
        public String hash(char[] p) { return new String(p); }
        public boolean matches(char[] p, String h) { return h.equals(new String(p)); }
    }

    private static final class MemoryIdempotencyStore implements IdempotencyStore {
        private final Map<IdempotencyScope, Entry> values = new HashMap<>();
        public synchronized Claim claim(IdempotencyScope scope, String hash, Instant now, Instant expires) {
            var value = values.get(scope);
            if (value == null) { values.put(scope, new Entry(hash, null)); return new Claim.Acquired(); }
            if (!value.hash.equals(hash)) return new Claim.HashMismatch();
            return value.result == null ? new Claim.InProgress() : new Claim.Completed(value.result);
        }
        public synchronized void complete(IdempotencyScope scope, String hash, StoredHttpResult result, Instant at) {
            values.put(scope, new Entry(hash, result));
        }
        public synchronized void release(IdempotencyScope scope, String hash) { values.remove(scope); }
        private record Entry(String hash, StoredHttpResult result) { }
    }
}
