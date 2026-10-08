package com.yunjihai.rag.identity.local;

import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.identity.DisabledUserSessionException;
import com.yunjihai.rag.identity.InMemorySessionVersionStore;
import com.yunjihai.rag.identity.SessionRevocationService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserAdministrationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-08T01:00:00Z");

    @Test
    void rejectsEquivalentUsernamesAfterNfkcWhitespaceAndCaseNormalization() {
        var fixture = fixture();
        fixture.service.createUser(" Alice ", "甲", chars("long-password-1"), UUID.randomUUID());
        assertThatThrownBy(() -> fixture.service.createUser("　ＡＬＩＣＥ　", "乙",
                chars("long-password-2"), UUID.randomUUID()))
                .isInstanceOf(UsernameConflictException.class);
    }

    @Test
    void preservesDisplayNameAndStoresOnlyNormalizedUsername() {
        var fixture = fixture();
        char[] password = chars("long-password-1");
        UserAccount account = fixture.service.createUser(" Ａｌｉｃｅ ", "  张三  ", password, UUID.randomUUID());
        assertThat(account.username()).isEqualTo("alice");
        assertThat(account.displayName()).isEqualTo("张三");
        assertThat(password).containsOnly('\0');
        assertThat(fixture.accounts.providers).containsEntry(account.id(), "LOCAL/alice");
    }

    @Test
    void rejectsPasswordsOutsideTwelveToOneHundredTwentyEightCharacters() {
        var fixture = fixture();
        assertThatThrownBy(() -> fixture.service.createUser("short", "短密码", chars("12345678901"), UUID.randomUUID()))
                .isInstanceOf(LocalIdentityValidationException.class);
        assertThatThrownBy(() -> fixture.service.createUser("long", "长密码", chars("x".repeat(129)), UUID.randomUUID()))
                .isInstanceOf(LocalIdentityValidationException.class);
    }

    @Test
    void disablingAndResettingPasswordInvalidateExistingSessions() {
        var fixture = fixture();
        UserAccount account = fixture.service.createUser("alice", "Alice", chars("long-password-1"), UUID.randomUUID());
        var first = fixture.sessions.issue(account.id());
        fixture.service.setEnabled(account.id(), false, UUID.randomUUID());
        assertThatThrownBy(() -> fixture.sessions.assertActive(first))
                .isInstanceOf(DisabledUserSessionException.class);
        fixture.service.setEnabled(account.id(), true, UUID.randomUUID());
        var second = fixture.sessions.issue(account.id());
        fixture.service.resetPassword(account.id(), chars("long-password-2"), UUID.randomUUID());
        assertThatThrownBy(() -> fixture.sessions.assertActive(second))
                .isInstanceOf(DisabledUserSessionException.class);
    }

    @Test
    void listsCreatedUsers() {
        var fixture = fixture();
        UserAccount alice = fixture.service.createUser(
                "alice", "Alice", chars("long-password-1"), UUID.randomUUID());

        assertThat(fixture.service.listUsers()).containsExactly(alice);
    }

    @Test
    void bcryptUsesCostTwelveAndVerifiesThePassword() {
        var hasher = new BCryptPasswordHasher();
        char[] password = chars("long-password-1");

        String encoded = hasher.hash(password);

        assertThat(encoded).matches("^\\$2[ayb]\\$12\\$.*");
        assertThat(hasher.matches(password, encoded)).isTrue();
        assertThat(hasher.matches(chars("long-password-2"), encoded)).isFalse();
    }

    @Test
    void creationAuditDoesNotPersistTheUsername() {
        var accounts = new MemoryAccounts();
        var credentials = new MemoryCredentials();
        AuditCommand[] captured = new AuditCommand[1];
        var service = new UserAdministrationService(
                accounts,
                credentials,
                new PlainHasher(),
                new SessionRevocationService(new InMemorySessionVersionStore()),
                command -> {
                    captured[0] = command;
                    return null;
                },
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.createUser("alice", "Alice", chars("long-password-1"), UUID.randomUUID());

        assertThat(captured[0].metadata()).isEmpty();
    }

    private static Fixture fixture() {
        var accounts = new MemoryAccounts();
        var credentials = new MemoryCredentials();
        var sessions = new SessionRevocationService(new InMemorySessionVersionStore());
        var service = new UserAdministrationService(accounts, credentials, new PlainHasher(), sessions,
                AuditSink.noop(), Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(accounts, credentials, sessions, service);
    }

    private static char[] chars(String value) { return value.toCharArray(); }
    private record Fixture(MemoryAccounts accounts, MemoryCredentials credentials,
                           SessionRevocationService sessions, UserAdministrationService service) { }

    private static final class MemoryAccounts implements UserAccountStore {
        private final Map<UUID, UserAccount> values = new HashMap<>();
        private final Map<UUID, String> providers = new HashMap<>();
        @Override public void insert(UserAccount account, String provider, String subject) {
            if (providers.containsValue(provider + "/" + subject)) throw new UsernameConflictException();
            values.put(account.id(), account);
            providers.put(account.id(), provider + "/" + subject);
        }
        @Override public Optional<UserAccount> findById(UUID userId) { return Optional.ofNullable(values.get(userId)); }
        @Override public List<UserAccount> list() { return new ArrayList<>(values.values()); }
        @Override public void setEnabled(UUID userId, boolean enabled, Instant at) {
            var old = values.get(userId);
            values.put(userId, new UserAccount(old.id(), old.username(), old.displayName(), enabled,
                    old.sessionVersion(), old.createdAt(), at));
        }
    }

    private static final class MemoryCredentials implements LocalCredentialStore {
        private final Map<UUID, LocalCredential> values = new HashMap<>();
        @Override public Optional<LocalCredential> lockForAuthentication(String username) {
            return values.values().stream().filter(value -> value.normalizedUsername().equals(username)).findFirst();
        }
        @Override public void insert(LocalCredential credential) { values.put(credential.userId(), credential); }
        @Override public void updateAuthenticationState(UUID userId, int failures, Instant windowStartedAt,
                                                         Instant lockedUntil, Instant at) { throw new UnsupportedOperationException(); }
        @Override public void resetAuthenticationState(UUID userId, Instant at) { throw new UnsupportedOperationException(); }
        @Override public void replacePassword(UUID userId, String passwordHash, Instant at) {
            var old = values.get(userId);
            values.put(userId, new LocalCredential(userId, old.normalizedUsername(), passwordHash, 0, null, null, at));
        }
    }

    private static final class PlainHasher implements PasswordHasher {
        @Override public String hash(char[] password) { return new String(password); }
        @Override public boolean matches(char[] password, String encoded) { return Arrays.equals(password, encoded.toCharArray()); }
    }
}
