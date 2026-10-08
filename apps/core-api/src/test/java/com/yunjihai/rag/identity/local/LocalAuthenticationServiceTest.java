package com.yunjihai.rag.identity.local;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalAuthenticationServiceTest {
    private static final Instant START = Instant.parse("2026-10-08T00:00:00Z");

    @Test
    void locksAccountForFifteenMinutesAfterFiveFailuresInOneWindow() {
        var fixture = fixture();
        fixture.addUser("Alice", "correct-password");

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> fixture.service.authenticate("ＡＬＩＣＥ", chars("wrong-password"), "10.0.0.1"))
                    .isInstanceOf(AuthenticationRejectedException.class);
        }

        assertThat(fixture.credentials.byUsername.get("alice").lockedUntil())
                .isEqualTo(START.plusSeconds(15 * 60));
        assertThatThrownBy(() -> fixture.service.authenticate("alice", chars("correct-password"), "10.0.0.2"))
                .isInstanceOf(AuthenticationRejectedException.class);
        fixture.clock.advanceSeconds(15 * 60);
        assertThat(fixture.service.authenticate("alice", chars("correct-password"), "10.0.0.2").userId())
                .isNotNull();
    }

    @Test
    void unknownUsernameUsesDummyHashAndReturnsSameFailure() {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.service.authenticate("missing", chars("wrong-password"), "10.0.0.3"))
                .isInstanceOf(AuthenticationRejectedException.class);

        assertThat(fixture.hasher.lastEncoded).isEqualTo("dummy-hash");
        assertThat(fixture.limiter.failuresBySource).containsEntry("10.0.0.3", 1);
    }

    @Test
    void successfulAuthenticationResetsFailuresAndClearsPasswordBuffer() {
        var fixture = fixture();
        UUID userId = fixture.addUser("alice", "correct-password");
        assertThatThrownBy(() -> fixture.service.authenticate("alice", chars("wrong-password"), "10.0.0.4"))
                .isInstanceOf(AuthenticationRejectedException.class);
        char[] password = chars("correct-password");

        AuthenticationResult result = fixture.service.authenticate("alice", password, "10.0.0.4");

        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.username()).isEqualTo("alice");
        assertThat(result.displayName()).isEqualTo("Alice");
        assertThat(password).containsOnly('\0');
        assertThat(fixture.credentials.byUsername.get("alice").failedAttempts()).isZero();
        assertThat(fixture.limiter.failuresBySource).doesNotContainKey("10.0.0.4");
    }

    @Test
    void rejectedAuthenticationAlsoClearsPasswordBuffer() {
        var fixture = fixture();
        char[] password = chars("wrong-password");

        assertThatThrownBy(() -> fixture.service.authenticate("missing", password, "10.0.0.5"))
                .isInstanceOf(AuthenticationRejectedException.class);

        assertThat(password).containsOnly('\0');
    }

    private static Fixture fixture() {
        var accounts = new MemoryAccounts();
        var credentials = new MemoryCredentials();
        var limiter = new MemoryLimiter();
        var hasher = new PlainHasher();
        var clock = new MutableClock(START);
        var service = new LocalAuthenticationService(
                accounts, credentials, limiter, hasher, clock, "dummy-hash");
        return new Fixture(accounts, credentials, limiter, hasher, clock, service);
    }

    private static char[] chars(String value) { return value.toCharArray(); }

    private record Fixture(MemoryAccounts accounts, MemoryCredentials credentials, MemoryLimiter limiter,
                           PlainHasher hasher, MutableClock clock, LocalAuthenticationService service) {
        UUID addUser(String username, String password) {
            UUID id = UUID.randomUUID();
            accounts.accounts.put(id, new UserAccount(id, username.toLowerCase(), "Alice", true, 0, START, START));
            credentials.insert(new LocalCredential(id, username.toLowerCase(), password, 0, null, null, START));
            return id;
        }
    }

    private static final class MemoryAccounts implements UserAccountStore {
        private final Map<UUID, UserAccount> accounts = new HashMap<>();
        @Override public void insert(UserAccount account, String provider, String subject) { accounts.put(account.id(), account); }
        @Override public Optional<UserAccount> findById(UUID userId) { return Optional.ofNullable(accounts.get(userId)); }
        @Override public java.util.List<UserAccount> list() { return java.util.List.copyOf(accounts.values()); }
        @Override public void setEnabled(UUID userId, boolean enabled, Instant at) {
            var old = accounts.get(userId);
            accounts.put(userId, new UserAccount(old.id(), old.username(), old.displayName(), enabled,
                    old.sessionVersion(), old.createdAt(), at));
        }
    }

    private static final class MemoryCredentials implements LocalCredentialStore {
        private final Map<String, LocalCredential> byUsername = new HashMap<>();
        @Override public Optional<LocalCredential> lockForAuthentication(String username) { return Optional.ofNullable(byUsername.get(username)); }
        @Override public void insert(LocalCredential credential) { byUsername.put(credential.normalizedUsername(), credential); }
        @Override public void updateAuthenticationState(UUID userId, int failures, Instant windowStartedAt,
                                                         Instant lockedUntil, Instant at) {
            var old = find(userId);
            byUsername.put(old.normalizedUsername(), new LocalCredential(userId, old.normalizedUsername(),
                    old.passwordHash(), failures, windowStartedAt, lockedUntil, old.passwordChangedAt()));
        }
        @Override public void resetAuthenticationState(UUID userId, Instant at) {
            var old = find(userId);
            byUsername.put(old.normalizedUsername(), new LocalCredential(userId, old.normalizedUsername(),
                    old.passwordHash(), 0, null, null, old.passwordChangedAt()));
        }
        @Override public void replacePassword(UUID userId, String passwordHash, Instant at) {
            var old = find(userId);
            byUsername.put(old.normalizedUsername(), new LocalCredential(userId, old.normalizedUsername(),
                    passwordHash, 0, null, null, at));
        }
        private LocalCredential find(UUID userId) {
            return byUsername.values().stream().filter(value -> value.userId().equals(userId)).findFirst().orElseThrow();
        }
    }

    private static final class MemoryLimiter implements LoginSourceRateLimiter {
        private final Map<String, Integer> failuresBySource = new HashMap<>();
        @Override public void assertAllowed(String sourceIp, Instant at) {
            if (failuresBySource.getOrDefault(sourceIp, 0) >= 20) throw new AuthenticationRejectedException();
        }
        @Override public void recordFailure(String sourceIp, Instant at) { failuresBySource.merge(sourceIp, 1, Integer::sum); }
        @Override public void reset(String sourceIp) { failuresBySource.remove(sourceIp); }
    }

    private static final class PlainHasher implements PasswordHasher {
        private String lastEncoded;
        @Override public String hash(char[] password) { return new String(password); }
        @Override public boolean matches(char[] password, String encoded) {
            lastEncoded = encoded;
            return Arrays.equals(password, encoded.toCharArray());
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advanceSeconds(long seconds) { instant = instant.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
