package com.yunjihai.rag.identity.web;

import com.yunjihai.rag.access.AclCommandRepository;
import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.access.role.RoleAssignmentService;
import com.yunjihai.rag.access.role.RoleAssignmentStore;
import com.yunjihai.rag.access.role.SpaceRole;
import com.yunjihai.rag.access.role.SystemRole;
import com.yunjihai.rag.api.idempotency.IdempotencyConflictException;
import com.yunjihai.rag.api.idempotency.IdempotencyScope;
import com.yunjihai.rag.api.idempotency.IdempotencyService;
import com.yunjihai.rag.api.idempotency.IdempotencyStore;
import com.yunjihai.rag.api.idempotency.StoredHttpResult;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.identity.InMemorySessionVersionStore;
import com.yunjihai.rag.identity.SessionRevocationService;
import com.yunjihai.rag.identity.local.LocalCredential;
import com.yunjihai.rag.identity.local.LocalCredentialStore;
import com.yunjihai.rag.identity.local.PasswordHasher;
import com.yunjihai.rag.identity.local.UserAccount;
import com.yunjihai.rag.identity.local.UserAccountStore;
import com.yunjihai.rag.identity.local.UserAdministrationService;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import com.yunjihai.rag.retrieval.projection.ProjectionChangePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminUserControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-08T11:00:00Z");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID ORDINARY = UUID.fromString("00000000-0000-7000-8000-000000000002");

    @Test
    void systemAdminCreatesListsDisablesAndResetsWithoutReturningPasswords() {
        var fixture = fixture(true);
        char[] password = "initial-password".toCharArray();
        var created = fixture.controller.create(
                authentication(ADMIN), "create-alice",
                new AdminUserController.CreateUserRequest("alice", "Alice", password));

        assertThat(created.username()).isEqualTo("alice");
        assertThat(Arrays.stream(created.getClass().getRecordComponents())
                .map(component -> component.getName()))
                .doesNotContain("password", "passwordHash");
        assertThat(password).containsOnly('\0');
        assertThat(fixture.controller.list(authentication(ADMIN), 1, 100))
                .extracting(AdminUserController.UserView::username).containsExactly("alice");

        fixture.controller.setEnabled(authentication(ADMIN), created.id(), "disable-alice",
                new AdminUserController.EnabledRequest(false));
        assertThat(fixture.accounts.findById(created.id()).orElseThrow().enabled()).isFalse();

        char[] replacement = "replacement-password".toCharArray();
        fixture.controller.resetPassword(authentication(ADMIN), created.id(), "reset-alice",
                new AdminUserController.ResetPasswordRequest(replacement));
        assertThat(replacement).containsOnly('\0');
        assertThat(fixture.credentials.passwords.get(created.id())).isEqualTo("replacement-password");
    }

    @Test
    void createIsReplayableAndSameKeyWithDifferentInputConflicts() {
        var fixture = fixture(true);
        var first = fixture.controller.create(authentication(ADMIN), "same-key",
                new AdminUserController.CreateUserRequest(
                        "alice", "Alice", "initial-password".toCharArray()));
        var replay = fixture.controller.create(authentication(ADMIN), "same-key",
                new AdminUserController.CreateUserRequest(
                        "alice", "Alice", "initial-password".toCharArray()));

        assertThat(replay).isEqualTo(first);
        assertThat(fixture.accounts.list()).hasSize(1);
        assertThatThrownBy(() -> fixture.controller.create(authentication(ADMIN), "same-key",
                new AdminUserController.CreateUserRequest(
                        "bob", "Bob", "initial-password".toCharArray())))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void nonAdminCannotUseAnyUserAdministrationEndpointAndPaginationIsBounded() {
        var nonAdminFixture = fixture(false);

        assertThatThrownBy(() -> nonAdminFixture.controller.list(authentication(ORDINARY), 1, 20))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> nonAdminFixture.controller.create(authentication(ORDINARY), "key",
                new AdminUserController.CreateUserRequest(
                        "alice", "Alice", "initial-password".toCharArray())))
                .isInstanceOf(AccessDeniedException.class);

        var adminFixture = fixture(true);
        assertThatThrownBy(() -> adminFixture.controller.list(authentication(ADMIN), 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adminFixture.controller.list(authentication(ADMIN), 1, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullRequestBodiesAndPasswordsAsBadInput() {
        var fixture = fixture(true);
        var authentication = authentication(ADMIN);

        assertThatThrownBy(() -> fixture.controller.create(authentication, "create-null", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.controller.create(authentication, "create-password-null",
                new AdminUserController.CreateUserRequest("alice", "Alice", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.controller.setEnabled(authentication, ORDINARY, "enable-null", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.controller.resetPassword(authentication, ORDINARY, "reset-null", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.controller.resetPassword(authentication, ORDINARY, "password-null",
                new AdminUserController.ResetPasswordRequest(null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Fixture fixture(boolean admin) {
        var accounts = new Accounts();
        var credentials = new Credentials();
        var users = new UserAdministrationService(
                accounts,
                credentials,
                new PlainHasher(),
                new SessionRevocationService(new InMemorySessionVersionStore()),
                command -> null,
                Clock.fixed(NOW, ZoneOffset.UTC));
        var roleStore = new Roles(admin ? Set.of(ADMIN) : Set.of());
        var roles = new RoleAssignmentService(
                roleStore, noopAcl(), noopAudit(), ProjectionChangePublisher.noop(),
                KnowledgeWriteAuthorization.allowAll());
        var json = new ObjectMapper();
        var idempotency = new IdempotencyService(
                new MemoryIdempotencyStore(), json, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(24));
        return new Fixture(new AdminUserController(users, roles, idempotency, json), accounts, credentials);
    }

    private static UsernamePasswordAuthenticationToken authentication(UUID userId) {
        return new UsernamePasswordAuthenticationToken(userId.toString(), "n/a", List.of());
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

    private static AuditSink noopAudit() { return command -> null; }

    private record Fixture(AdminUserController controller, Accounts accounts, Credentials credentials) { }

    private static final class Accounts implements UserAccountStore {
        private final Map<UUID, UserAccount> values = new HashMap<>();
        public void insert(UserAccount account, String provider, String subject) { values.put(account.id(), account); }
        public Optional<UserAccount> findById(UUID id) { return Optional.ofNullable(values.get(id)); }
        public List<UserAccount> list() { return values.values().stream().sorted(java.util.Comparator.comparing(UserAccount::username)).toList(); }
        public void setEnabled(UUID id, boolean enabled, Instant at) {
            var old = values.get(id);
            values.put(id, new UserAccount(old.id(), old.username(), old.displayName(), enabled,
                    old.sessionVersion(), old.createdAt(), at));
        }
    }

    private static final class Credentials implements LocalCredentialStore {
        private final Map<UUID, String> passwords = new HashMap<>();
        public Optional<LocalCredential> lockForAuthentication(String username) { return Optional.empty(); }
        public void insert(LocalCredential credential) { passwords.put(credential.userId(), credential.passwordHash()); }
        public void updateAuthenticationState(UUID id, int f, Instant w, Instant l, Instant at) { }
        public void resetAuthenticationState(UUID id, Instant at) { }
        public void replacePassword(UUID id, String hash, Instant at) { passwords.put(id, hash); }
    }

    private static final class PlainHasher implements PasswordHasher {
        public String hash(char[] password) { return new String(password); }
        public boolean matches(char[] password, String encoded) { return encoded.equals(new String(password)); }
    }

    private static final class Roles implements RoleAssignmentStore {
        private final Set<UUID> admins;
        private Roles(Set<UUID> admins) { this.admins = admins; }
        public boolean hasSystemRole(UUID user, SystemRole role) { return admins.contains(user); }
        public boolean hasAnySystemRole(SystemRole role) { return !admins.isEmpty(); }
        public Set<SpaceRole> activeSpaceRoles(UUID user, UUID space) { return Set.of(); }
        public void grantSystemRole(UUID u, SystemRole r, UUID a, Instant at) { }
        public void grantSpaceRole(UUID s, UUID u, SpaceRole r, UUID a, Instant at) { }
        public void revokeSpaceRole(UUID s, UUID u, SpaceRole r, UUID a, Instant at) { }
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
