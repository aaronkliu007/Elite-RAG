package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.api.idempotency.IdempotencyScope;
import com.yunjihai.rag.api.idempotency.IdempotencyStore;
import com.yunjihai.rag.api.idempotency.StoredHttpResult;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcIdempotencyIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-08T09:00:00Z");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final String HASH = "a".repeat(64);
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_idempotency_test")
            .withUsername("migration_owner")
            .withPassword("migration_test_only");

    private static AnnotationConfigApplicationContext context;
    private static JdbcClient jdbc;
    private static JdbcIdempotencyStore store;

    @BeforeAll
    static void startDatabase() throws Exception {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_idempotency_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        TestConfig.runtimeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only");
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = context.getBean(JdbcClient.class);
        store = context.getBean(JdbcIdempotencyStore.class);
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, 'actor', 'Actor')")
                .param("id", ACTOR).update();
    }

    @AfterEach
    void clearRecords() {
        jdbc.sql("DELETE FROM idempotency_record").update();
    }

    @AfterAll
    static void stopDatabase() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @Test
    void claimsReplaysAndRejectsDifferentRequestHash() {
        var scope = scope("basic");
        assertThat(store.claim(scope, HASH, NOW, NOW.plusSeconds(86_400)))
                .isInstanceOf(IdempotencyStore.Claim.Acquired.class);
        assertThat(store.claim(scope, HASH, NOW.plusSeconds(1), NOW.plusSeconds(86_401)))
                .isInstanceOf(IdempotencyStore.Claim.InProgress.class);
        assertThat(store.claim(scope, "b".repeat(64), NOW.plusSeconds(1), NOW.plusSeconds(86_401)))
                .isInstanceOf(IdempotencyStore.Claim.HashMismatch.class);

        var result = new StoredHttpResult(200, "{\"value\":\"saved\"}");
        store.complete(scope, HASH, result, NOW.plusSeconds(2));

        var replay = (IdempotencyStore.Claim.Completed) store.claim(
                scope, HASH, NOW.plusSeconds(3), NOW.plusSeconds(86_403));
        assertThat(replay.result().status()).isEqualTo(200);
        assertThat(new tools.jackson.databind.ObjectMapper().readTree(replay.result().responseBody()))
                .isEqualTo(new tools.jackson.databind.ObjectMapper().readTree(result.responseBody()));
    }

    @Test
    void expiredClaimCanBeAcquiredAgainAndRetentionIsAtLeastTwentyFourHours() {
        var scope = scope("expiry");
        Instant expiry = NOW.plusSeconds(86_400);
        assertThat(store.claim(scope, HASH, NOW, expiry))
                .isInstanceOf(IdempotencyStore.Claim.Acquired.class);
        OffsetDateTime storedExpiry = jdbc.sql("SELECT expires_at FROM idempotency_record")
                .query(OffsetDateTime.class).single();
        assertThat(storedExpiry.toInstant()).isEqualTo(expiry);

        assertThat(store.claim(scope, "b".repeat(64), expiry, expiry.plusSeconds(86_400)))
                .isInstanceOf(IdempotencyStore.Claim.Acquired.class);
    }

    @Test
    void concurrentClaimsHaveExactlyOneExecutor() throws Exception {
        var scope = scope("concurrent");
        var ready = new CountDownLatch(2);
        var fire = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(1, 2).stream().map(ignored -> executor.submit(() -> {
                ready.countDown();
                fire.await(5, TimeUnit.SECONDS);
                return store.claim(scope, HASH, NOW, NOW.plusSeconds(86_400));
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            fire.countDown();
            var claims = tasks.stream().map(task -> {
                try {
                    return task.get(10, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }).toList();
            assertThat(claims.stream().filter(IdempotencyStore.Claim.Acquired.class::isInstance)).hasSize(1);
            assertThat(claims.stream().filter(IdempotencyStore.Claim.InProgress.class::isInstance)).hasSize(1);
        }
    }

    private static IdempotencyScope scope(String key) {
        return new IdempotencyScope(ACTOR, "document.upload", key);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfig {
        private static DataSource runtimeDataSource;
        @Bean DataSource dataSource() { return runtimeDataSource; }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean JdbcClient jdbcClient(DataSource dataSource) { return JdbcClient.create(dataSource); }
        @Bean JdbcIdempotencyStore store(JdbcClient jdbc) { return new JdbcIdempotencyStore(jdbc); }
    }
}
