package com.yunjihai.rag.identity.local;

import com.yunjihai.rag.access.AclCommandRepository;
import com.yunjihai.rag.access.role.RoleAssignmentService;
import com.yunjihai.rag.access.role.RoleAssignmentStore;
import com.yunjihai.rag.access.role.SpaceRole;
import com.yunjihai.rag.access.role.SystemRole;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.identity.InMemorySessionVersionStore;
import com.yunjihai.rag.identity.SessionRevocationService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapAdministratorTest {
    private static final Instant NOW = Instant.parse("2026-10-08T05:00:00Z");

    @Test
    void acceptsRequestedDefaultAdministratorCredentialsDuringBootstrapOnly() {
        var fixture = fixture(new BootstrapAdminProperties("admin", "admin", "系统管理员"));
        fixture.bootstrap.initialize();
        assertThat(fixture.accounts.values).singleElement().satisfies(account ->
                assertThat(account.username()).isEqualTo("admin"));
        assertThat(fixture.credentials.values).singleElement().satisfies(credential ->
                {
                    assertThat(credential.passwordHash()).isEqualTo("admin");
                    assertThat(credential.passwordChangeRequired()).isTrue();
                });
    }

    @Test
    void createsExactlyOneAdministratorWhenNoneExistsAndConfigurationIsComplete() {
        var fixture = fixture(new BootstrapAdminProperties("root", "a-strong-password", "平台管理员"));

        fixture.bootstrap.initialize();
        fixture.bootstrap.initialize();

        assertThat(fixture.accounts.values).singleElement().satisfies(account -> {
            assertThat(account.username()).isEqualTo("root");
            assertThat(account.displayName()).isEqualTo("平台管理员");
        });
        assertThat(fixture.roles.systemAdministratorId).isEqualTo(fixture.accounts.values.getFirst().id());
        assertThat(fixture.credentials.values).hasSize(1);
    }

    @Test
    void existingAdministratorPreventsBootstrapCredentialsFromOverwritingAnything() {
        var fixture = fixture(new BootstrapAdminProperties("replacement", "a-strong-password", "替代管理员"));
        fixture.roles.systemAdministratorId = UUID.randomUUID();

        fixture.bootstrap.initialize();

        assertThat(fixture.accounts.values).isEmpty();
        assertThat(fixture.credentials.values).isEmpty();
    }

    @Test
    void missingAdministratorAndIncompleteConfigurationFailClosed() {
        var fixture = fixture(new BootstrapAdminProperties("", "", ""));

        assertThatThrownBy(fixture.bootstrap::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("引导管理员");
        assertThat(fixture.accounts.values).isEmpty();
    }

    private static Fixture fixture(BootstrapAdminProperties properties) {
        var accounts = new Accounts();
        var credentials = new Credentials();
        var roleStore = new Roles();
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var users = new UserAdministrationService(
                accounts, credentials, passwordHasher(),
                new SessionRevocationService(new InMemorySessionVersionStore()),
                AuditSink.noop(), clock);
        AclCommandRepository acl = new AclCommandRepository() {
            @Override public boolean isWithinSpaceScope(UUID spaceId, com.yunjihai.rag.access.PrincipalRef principal, Instant at) { return false; }
            @Override public void grantSpace(UUID spaceId, com.yunjihai.rag.access.PrincipalRef principal, Instant validUntil, Instant at) { }
            @Override public void grantDocument(UUID spaceId, UUID documentId, com.yunjihai.rag.access.PrincipalRef principal, Instant validUntil, Instant at) { }
            @Override public void revokeSpace(UUID spaceId, com.yunjihai.rag.access.PrincipalRef principal, Instant at) { }
            @Override public void revokeDocument(UUID spaceId, UUID documentId, com.yunjihai.rag.access.PrincipalRef principal, Instant at) { }
        };
        var roles = new RoleAssignmentService(
                roleStore, acl, AuditSink.noop(),
                com.yunjihai.rag.retrieval.projection.ProjectionChangePublisher.noop(),
                com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization.allowAll());
        return new Fixture(new BootstrapAdministrator(users, roles, properties, clock), accounts, credentials, roleStore);
    }

    private static PasswordHasher passwordHasher() {
        return new PasswordHasher() {
            @Override public String hash(char[] password) { return new String(password); }
            @Override public boolean matches(char[] password, String encoded) { return false; }
        };
    }

    private record Fixture(BootstrapAdministrator bootstrap, Accounts accounts, Credentials credentials, Roles roles) { }

    private static final class Accounts implements UserAccountStore {
        private final List<UserAccount> values = new ArrayList<>();
        @Override public void insert(UserAccount account, String provider, String subject) { values.add(account); }
        @Override public Optional<UserAccount> findById(UUID userId) {
            return values.stream().filter(value -> value.id().equals(userId)).findFirst();
        }
        @Override public List<UserAccount> list() { return List.copyOf(values); }
        @Override public void setEnabled(UUID userId, boolean enabled, Instant at) { }
    }

    private static final class Credentials implements LocalCredentialStore {
        private final List<LocalCredential> values = new ArrayList<>();
        @Override public Optional<LocalCredential> lockForAuthentication(String username) { return Optional.empty(); }
        @Override public void insert(LocalCredential credential) { values.add(credential); }
        @Override public void updateAuthenticationState(UUID userId, int failures, Instant window, Instant locked, Instant at) { }
        @Override public void resetAuthenticationState(UUID userId, Instant at) { }
        @Override public void replacePassword(UUID userId, String hash, Instant at) { }
    }

    private static final class Roles implements RoleAssignmentStore {
        private UUID systemAdministratorId;
        @Override public boolean hasSystemRole(UUID userId, SystemRole role) {
            return userId.equals(systemAdministratorId) && role == SystemRole.SYSTEM_ADMIN;
        }
        @Override public boolean hasAnySystemRole(SystemRole role) {
            return systemAdministratorId != null && role == SystemRole.SYSTEM_ADMIN;
        }
        @Override public Set<SpaceRole> activeSpaceRoles(UUID userId, UUID spaceId) { return Set.of(); }
        @Override public void grantSystemRole(UUID userId, SystemRole role, UUID actorId, Instant at) {
            systemAdministratorId = userId;
        }
        @Override public void grantSpaceRole(UUID spaceId, UUID userId, SpaceRole role, UUID actorId, Instant at) { }
        @Override public void revokeSpaceRole(UUID spaceId, UUID userId, SpaceRole role, UUID actorId, Instant at) { }
    }
}
