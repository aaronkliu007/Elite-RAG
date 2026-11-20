package com.yunjihai.rag.identity.web;

import com.yunjihai.rag.RagApplication;
import com.yunjihai.rag.identity.InMemorySessionVersionStore;
import com.yunjihai.rag.identity.SessionRevocationService;
import com.yunjihai.rag.identity.SessionVersionStore;
import com.yunjihai.rag.identity.local.LocalAuthenticationService;
import com.yunjihai.rag.identity.local.LocalCredential;
import com.yunjihai.rag.identity.local.LocalCredentialStore;
import com.yunjihai.rag.identity.local.LoginSourceRateLimiter;
import com.yunjihai.rag.identity.local.PasswordHasher;
import com.yunjihai.rag.identity.local.UserAccount;
import com.yunjihai.rag.identity.local.UserAccountStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = {RagApplication.class, SessionSecurityIntegrationTest.TestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
                "rag.session.enabled=true",
                "rag.session.absolute-timeout=8h"
        })
class SessionSecurityIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-08T04:00:00Z");

    @LocalServerPort
    private int port;
    @Autowired
    private MutableClock clock;
    @Autowired
    private InMemorySessionVersionStore versions;
    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final tools.jackson.databind.ObjectMapper objectMapper = new tools.jackson.databind.ObjectMapper();

    @BeforeEach
    void resetState() {
        clock.set(START);
        versions.setEnabled(TestConfig.USER_ID, true);
        TestConfig.REQUIRED.set(false);
        TestConfig.HASH.set("correct-password");
    }

    @Test
    void requiredPasswordChangeBlocksBusinessApisUntilPasswordIsChanged() throws Exception {
        TestConfig.REQUIRED.set(true);
        CsrfSession csrf = csrf();
        var login = post("/api/v1/session/login", csrf.cookie(), csrf,
                "{\"username\":\"alice\",\"password\":\"correct-password\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(login.body()).path("passwordChangeRequired").asBoolean()).isTrue();
        String activeCookie = cookie(login);
        assertThat(get("/api/v1/admin/users", activeCookie).statusCode()).isEqualTo(403);
        assertThat(get("/%61pi/v1/admin/users", activeCookie).statusCode()).isIn(400, 403);
        assertThat(get("/api/v1/session/me", activeCookie).statusCode()).isEqualTo(200);
        assertThat(post("/api/v1/session/change-initial-password", activeCookie, null,
                "{\"currentPassword\":\"correct-password\",\"newPassword\":\"a-new-password-2026\"}").statusCode()).isEqualTo(403);
        assertThat(post("/api/v1/session/change-initial-password", activeCookie, csrf,
                "{\"currentPassword\":\"wrong\",\"newPassword\":\"a-new-password-2026\"}").statusCode()).isEqualTo(400);
        assertThat(post("/api/v1/session/change-initial-password", activeCookie, csrf,
                "{\"currentPassword\":\"correct-password\",\"newPassword\":\"short\"}").statusCode()).isEqualTo(400);
        var change = post("/api/v1/session/change-initial-password", activeCookie, csrf,
                "{\"currentPassword\":\"correct-password\",\"newPassword\":\"a-new-password-2026\"}");
        assertThat(change.statusCode()).isEqualTo(204);
        assertThat(get("/api/v1/session/me", activeCookie).statusCode()).isEqualTo(401);
        CsrfSession fresh = csrf();
        var relogin = post("/api/v1/session/login", fresh.cookie(), fresh,
                "{\"username\":\"alice\",\"password\":\"a-new-password-2026\"}");
        assertThat(relogin.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(relogin.body()).path("passwordChangeRequired").asBoolean()).isFalse();
    }

    @Test
    void protectsApiWithCsrfAndIssuesHardenedRotatedSessionCookie() throws Exception {
        var unauthenticated = get("/api/v1/session/me", null);
        assertError(unauthenticated, 401, "UNAUTHORIZED");
        CsrfSession csrf = csrf();
        assertThat(csrf.setCookie()).contains("HttpOnly", "Secure", "SameSite=Lax");

        var withoutCsrf = post("/api/v1/session/login", csrf.cookie(), null,
                "{\"username\":\"alice\",\"password\":\"correct-password\"}");
        assertError(withoutCsrf, 403, "FORBIDDEN");

        var wrongPassword = post("/api/v1/session/login", csrf.cookie(), csrf,
                "{\"username\":\"alice\",\"password\":\"wrong-password\"}");
        assertError(wrongPassword, 401, "UNAUTHORIZED");
        assertThat(objectMapper.readTree(wrongPassword.body()).path("message").asText())
                .isEqualTo("用户名或密码不正确，或账号暂时不可用");

        var login = post("/api/v1/session/login", csrf.cookie(), csrf,
                "{\"username\":\"alice\",\"password\":\"correct-password\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        String rotatedCookie = cookie(login);
        assertThat(rotatedCookie).isNotEqualTo(csrf.cookie());
        assertThat(login.headers().firstValue("set-cookie").orElseThrow())
                .contains("HttpOnly", "Secure", "SameSite=Lax");
        assertThat(get("/api/v1/session/me", rotatedCookie).statusCode()).isEqualTo(200);
        assertThat(environment.getProperty("server.servlet.session.timeout")).isEqualTo("30m");
    }

    @Test
    void rejectsRevokedAndAbsolutelyExpiredSessionsOnTheNextRequest() throws Exception {
        CsrfSession firstCsrf = csrf();
        String firstCookie = cookie(post("/api/v1/session/login", firstCsrf.cookie(), firstCsrf,
                "{\"username\":\"alice\",\"password\":\"correct-password\"}"));
        versions.advanceVersion(TestConfig.USER_ID);
        assertThat(get("/api/v1/session/me", firstCookie).statusCode()).isEqualTo(401);

        versions.setEnabled(TestConfig.USER_ID, true);
        CsrfSession secondCsrf = csrf();
        String secondCookie = cookie(post("/api/v1/session/login", secondCsrf.cookie(), secondCsrf,
                "{\"username\":\"alice\",\"password\":\"correct-password\"}"));
        versions.setEnabled(TestConfig.USER_ID, false);
        assertThat(get("/api/v1/session/me", secondCookie).statusCode()).isEqualTo(401);

        versions.setEnabled(TestConfig.USER_ID, true);
        CsrfSession thirdCsrf = csrf();
        String thirdCookie = cookie(post("/api/v1/session/login", thirdCsrf.cookie(), thirdCsrf,
                "{\"username\":\"alice\",\"password\":\"correct-password\"}"));
        clock.set(START.plusSeconds(8 * 60 * 60L + 1));
        assertThat(get("/api/v1/session/me", thirdCookie).statusCode()).isEqualTo(401);
    }

    private CsrfSession csrf() throws Exception {
        HttpResponse<String> response = get("/api/v1/session/csrf", null);
        assertThat(response.statusCode()).isEqualTo(200);
        tools.jackson.databind.JsonNode json = objectMapper.readTree(response.body());
        return new CsrfSession(cookie(response),
                response.headers().firstValue("set-cookie").orElseThrow(),
                json.get("headerName").asText(), json.get("token").asText());
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).GET();
        if (cookie != null) builder.header("Cookie", cookie);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String cookie, CsrfSession csrf, String body) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (csrf != null) builder.header(csrf.headerName(), csrf.token());
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        tools.jackson.databind.JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("code").asText()).isEqualTo(code);
        assertThat(json.get("traceId").asText())
                .isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
    }

    private static String cookie(HttpResponse<?> response) {
        return response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }

    private record CsrfSession(String cookie, String setCookie, String headerName, String token) { }

    @Configuration(proxyBeanMethods = false)
    @Import({SecurityConfiguration.class, SessionController.class})
    static class TestConfig {
        private static final UUID USER_ID = UUID.fromString("0199c5f2-31c0-7000-8000-000000000001");
        private static final java.util.concurrent.atomic.AtomicBoolean REQUIRED = new java.util.concurrent.atomic.AtomicBoolean();
        private static final AtomicReference<String> HASH = new AtomicReference<>("correct-password");

        @Bean MutableClock clock() { return new MutableClock(START); }
        @Bean InMemorySessionVersionStore versions() { return new InMemorySessionVersionStore(); }
        @Bean SessionRevocationService sessions(SessionVersionStore versions) {
            return new SessionRevocationService(versions);
        }
        @Bean UserAccountStore accounts() {
            UserAccount account = new UserAccount(USER_ID, "alice", "Alice", true, 0, START, START);
            return new UserAccountStore() {
                @Override public void insert(UserAccount value, String provider, String subject) { throw new UnsupportedOperationException(); }
                @Override public Optional<UserAccount> findById(UUID userId) {
                    return USER_ID.equals(userId) ? Optional.of(account) : Optional.empty();
                }
                @Override public List<UserAccount> list() { return List.of(account); }
                @Override public void setEnabled(UUID userId, boolean enabled, Instant at) { throw new UnsupportedOperationException(); }
            };
        }
        @Bean LocalCredentialStore credentials() {
            return new LocalCredentialStore() {
                @Override public Optional<LocalCredential> lockForAuthentication(String username) {
                    return "alice".equals(username) ? Optional.of(new LocalCredential(
                            USER_ID, "alice", HASH.get(), 0, null, null, START, REQUIRED.get())) : Optional.empty();
                }
                @Override public void insert(LocalCredential value) { throw new UnsupportedOperationException(); }
                @Override public void updateAuthenticationState(UUID userId, int failures, Instant window, Instant locked, Instant at) { }
                @Override public void resetAuthenticationState(UUID userId, Instant at) { }
                @Override public void replacePassword(UUID userId, String hash, Instant at) { HASH.set(hash); REQUIRED.set(false); }
            };
        }
        @Bean PasswordHasher passwordHasher() {
            return new PasswordHasher() {
                @Override public String hash(char[] password) { return new String(password); }
                @Override public boolean matches(char[] password, String encoded) {
                    return Arrays.equals(password, encoded.toCharArray());
                }
            };
        }
        @Bean LoginSourceRateLimiter sourceLimiter() {
            return new LoginSourceRateLimiter() {
                @Override public void assertAllowed(String sourceIp, Instant at) { }
                @Override public void recordFailure(String sourceIp, Instant at) { }
                @Override public void reset(String sourceIp) { }
            };
        }
        @Bean LocalAuthenticationService authentication(
                UserAccountStore accounts,
                LocalCredentialStore credentials,
                LoginSourceRateLimiter limiter,
                PasswordHasher hasher,
                Clock clock) {
            return new LocalAuthenticationService(accounts, credentials, limiter, hasher, clock, "dummy-hash");
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        MutableClock(Instant initial) { instant = new AtomicReference<>(initial); }
        void set(Instant value) { instant.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }
}
