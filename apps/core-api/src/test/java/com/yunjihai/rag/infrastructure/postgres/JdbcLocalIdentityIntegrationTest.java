package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.identity.SessionRevocationService;
import com.yunjihai.rag.identity.local.AuthenticationRejectedException;
import com.yunjihai.rag.identity.local.LocalAuthenticationService;
import com.yunjihai.rag.identity.local.PasswordHasher;
import com.yunjihai.rag.identity.local.UserAdministrationService;
import com.yunjihai.rag.identity.local.UsernameConflictException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcLocalIdentityIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_local_identity_test")
            .withUsername("migration_owner")
            .withPassword("migration_test_only");
    private static final Instant NOW = Instant.parse("2026-10-08T02:00:00Z");
    private static final byte[] SOURCE_HMAC_KEY = "0123456789abcdef0123456789abcdef".getBytes();
    private static DataSource runtimeDataSource;
    private static AnnotationConfigApplicationContext context;
    private static JdbcClient jdbc;
    private static UserAdministrationService administration;
    private static LocalAuthenticationService authentication;
    private static JdbcLoginSourceRateLimiter sourceLimiter;

    @BeforeAll
    static void setUp() throws SQLException {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_local_identity_test SET rag.runtime_role = 'rag_runtime_test'");
            statement.execute("REVOKE TEMPORARY ON DATABASE rag_local_identity_test FROM PUBLIC");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        runtimeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only");
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = context.getBean(JdbcClient.class);
        administration = context.getBean(UserAdministrationService.class);
        authentication = context.getBean(LocalAuthenticationService.class);
        sourceLimiter = context.getBean(JdbcLoginSourceRateLimiter.class);
    }

    @AfterEach
    void clearRows() {
        jdbc.sql("DELETE FROM login_source_attempt").update();
        jdbc.sql("DELETE FROM app_user").update();
    }

    @AfterAll
    static void tearDown() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @Test
    void createsListsAndAuthenticatesLocalUser() {
        UUID actorId = UUID.randomUUID();
        var account = administration.createUser(
                "　ＡＬＩＣＥ　", "  张三  ", chars("long-password-1"), actorId);

        assertThat(administration.listUsers()).containsExactly(account);
        assertThat(authentication.authenticate(
                "alice", chars("long-password-1"), "10.0.0.1").userId()).isEqualTo(account.id());
        assertThat(jdbc.sql("SELECT provider FROM external_identity WHERE user_id = :id")
                .param("id", account.id()).query(String.class).single()).isEqualTo("LOCAL");
    }

    @Test
    void translatesEquivalentUsernameConstraintToStableConflict() {
        administration.createUser("alice", "Alice", chars("long-password-1"), UUID.randomUUID());

        assertThatThrownBy(() -> administration.createUser(
                "　ＡＬＩＣＥ　", "Another", chars("long-password-2"), UUID.randomUUID()))
                .isInstanceOf(UsernameConflictException.class);
        assertThat(administration.listUsers()).hasSize(1);
    }

    @Test
    void twentySourceFailuresBlockForFifteenMinutesAndPersistOnlyHmac() {
        String sourceIp = "10.10.20.30";
        for (int attempt = 0; attempt < 19; attempt++) {
            sourceLimiter.recordFailure(sourceIp, NOW);
            sourceLimiter.assertAllowed(sourceIp, NOW);
        }

        sourceLimiter.recordFailure(sourceIp, NOW);

        assertThatThrownBy(() -> sourceLimiter.assertAllowed(sourceIp, NOW))
                .isInstanceOf(AuthenticationRejectedException.class);
        String stored = jdbc.sql("SELECT source_hash FROM login_source_attempt")
                .query(String.class).single();
        assertThat(stored).matches("^[0-9a-f]{64}$").doesNotContain(sourceIp);
        sourceLimiter.assertAllowed(sourceIp, NOW.plusSeconds(15 * 60));
    }

    @Test
    void concurrentFailuresCannotBypassTheFiveAttemptAccountLock() throws Exception {
        var account = administration.createUser(
                "alice", "Alice", chars("long-password-1"), UUID.randomUUID());
        try (var executor = Executors.newFixedThreadPool(5)) {
            var futures = java.util.stream.IntStream.range(0, 5)
                    .mapToObj(index -> executor.submit(() -> {
                        try {
                            authentication.authenticate(
                                    "alice", chars("wrong-password"), "10.0.0." + (index + 1));
                        } catch (AuthenticationRejectedException expected) {
                            return;
                        }
                        throw new AssertionError("错误密码不应认证成功");
                    })).toList();
            for (var future : futures) future.get();
        }

        assertThat(jdbc.sql("SELECT failed_attempts FROM local_credential WHERE user_id = :id")
                .param("id", account.id()).query(Integer.class).single()).isEqualTo(5);
        assertThat(jdbc.sql("SELECT locked_until FROM local_credential WHERE user_id = :id")
                .param("id", account.id()).query(java.time.OffsetDateTime.class).single().toInstant())
                .isEqualTo(NOW.plusSeconds(15 * 60));
    }

    private static char[] chars(String value) {
        return value.toCharArray();
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfig {
        @Bean DataSource dataSource() { return runtimeDataSource; }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean JdbcClient jdbcClient(DataSource dataSource) { return JdbcClient.create(dataSource); }
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean JdbcUserAccountStore accounts(JdbcClient jdbc) { return new JdbcUserAccountStore(jdbc); }
        @Bean JdbcLocalCredentialStore credentials(JdbcClient jdbc) { return new JdbcLocalCredentialStore(jdbc); }
        @Bean JdbcLoginSourceRateLimiter sourceLimiter(JdbcClient jdbc) {
            return new JdbcLoginSourceRateLimiter(jdbc, SOURCE_HMAC_KEY);
        }
        @Bean JdbcSessionVersionStore sessionVersions(JdbcClient jdbc) { return new JdbcSessionVersionStore(jdbc); }
        @Bean PasswordHasher passwordHasher() { return new PlainHasher(); }
        @Bean UserAdministrationService administration(
                JdbcUserAccountStore accounts,
                JdbcLocalCredentialStore credentials,
                PasswordHasher hasher,
                JdbcSessionVersionStore versions,
                Clock clock) {
            return new UserAdministrationService(
                    accounts, credentials, hasher, new SessionRevocationService(versions), AuditSink.noop(), clock);
        }
        @Bean LocalAuthenticationService authentication(
                JdbcUserAccountStore accounts,
                JdbcLocalCredentialStore credentials,
                JdbcLoginSourceRateLimiter limiter,
                PasswordHasher hasher,
                Clock clock) {
            return new LocalAuthenticationService(accounts, credentials, limiter, hasher, clock, "dummy-hash");
        }
    }

    private static final class PlainHasher implements PasswordHasher {
        @Override public String hash(char[] password) { return new String(password); }
        @Override public boolean matches(char[] password, String encoded) {
            return Arrays.equals(password, encoded.toCharArray());
        }
    }
}
