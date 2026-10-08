package com.yunjihai.rag.identity.web;

import com.yunjihai.rag.identity.InMemorySessionVersionStore;
import com.yunjihai.rag.identity.SessionRevocationService;
import com.yunjihai.rag.identity.local.LocalAuthenticationService;
import com.yunjihai.rag.identity.local.LocalCredential;
import com.yunjihai.rag.identity.local.LocalCredentialStore;
import com.yunjihai.rag.identity.local.LoginSourceRateLimiter;
import com.yunjihai.rag.identity.local.PasswordHasher;
import com.yunjihai.rag.identity.local.UserAccount;
import com.yunjihai.rag.identity.local.UserAccountStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SessionControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-08T03:00:00Z");

    @Test
    void loginRotatesSessionAndStoresInternalUuidPrincipal() {
        UUID userId = UUID.randomUUID();
        UserAccount account = new UserAccount(userId, "alice", "Alice", true, 7, NOW, NOW);
        LocalCredential credential = new LocalCredential(
                userId, "alice", "correct-password", 0, null, null, NOW);
        var versions = new InMemorySessionVersionStore();
        for (int index = 0; index < 7; index++) versions.advanceVersion(userId);
        var controller = new SessionController(
                authentication(account, credential),
                new SessionRevocationService(versions),
                new HttpSessionSecurityContextRepository(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        String previousSessionId = request.getSession(true).getId();
        char[] password = "correct-password".toCharArray();

        SessionController.SessionView result = controller.login(
                new SessionController.LoginRequest("alice", password),
                request,
                new MockHttpServletResponse());

        assertThat(request.getSession(false).getId()).isNotEqualTo(previousSessionId);
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.username()).isEqualTo("alice");
        assertThat(password).containsOnly('\0');
        Object context = request.getSession(false).getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context).isNotNull();
        assertThat(new SessionPrincipal(userId, "alice", "Alice", 7, NOW).getName())
                .isEqualTo(userId.toString());
    }

    private static LocalAuthenticationService authentication(UserAccount account, LocalCredential credential) {
        UserAccountStore accounts = new UserAccountStore() {
            @Override public void insert(UserAccount value, String provider, String subject) { throw new UnsupportedOperationException(); }
            @Override public Optional<UserAccount> findById(UUID userId) {
                return account.id().equals(userId) ? Optional.of(account) : Optional.empty();
            }
            @Override public List<UserAccount> list() { return List.of(account); }
            @Override public void setEnabled(UUID userId, boolean enabled, Instant at) { throw new UnsupportedOperationException(); }
        };
        LocalCredentialStore credentials = new LocalCredentialStore() {
            @Override public Optional<LocalCredential> lockForAuthentication(String username) {
                return credential.normalizedUsername().equals(username) ? Optional.of(credential) : Optional.empty();
            }
            @Override public void insert(LocalCredential value) { throw new UnsupportedOperationException(); }
            @Override public void updateAuthenticationState(UUID userId, int failures, Instant window, Instant locked, Instant at) { }
            @Override public void resetAuthenticationState(UUID userId, Instant at) { }
            @Override public void replacePassword(UUID userId, String hash, Instant at) { throw new UnsupportedOperationException(); }
        };
        LoginSourceRateLimiter limiter = new LoginSourceRateLimiter() {
            @Override public void assertAllowed(String sourceIp, Instant at) { }
            @Override public void recordFailure(String sourceIp, Instant at) { }
            @Override public void reset(String sourceIp) { }
        };
        PasswordHasher hasher = new PasswordHasher() {
            @Override public String hash(char[] password) { return new String(password); }
            @Override public boolean matches(char[] password, String encoded) {
                return Arrays.equals(password, encoded.toCharArray());
            }
        };
        return new LocalAuthenticationService(
                accounts, credentials, limiter, hasher, Clock.fixed(NOW, ZoneOffset.UTC), "dummy-hash");
    }
}
