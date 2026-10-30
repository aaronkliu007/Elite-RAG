package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.generation.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class JdbcGenerationAttemptStoreIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_generation_test").withUsername("migration_owner").withPassword("migration_test_only");
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static GenerationAttemptStore attempts;

    @BeforeAll static void start() throws Exception {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_generation_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only");
        jdbc = JdbcClient.create(dataSource);
        attempts = new JdbcGenerationAttemptStore(jdbc);
    }

    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test void persistsEveryCheckpointAcrossAdapterInstances() {
        var key = seed(100, "persist-checkpoints");
        var started = GenerationAttempt.start(id(106), key, NOW);
        assertThat(attempts.register(started)).isEqualTo(new GenerationAttemptRegistration(started, true));
        var retrieving = started.startRetrieval(NOW.plusSeconds(1));
        attempts.replace(retrieving, started.revision());
        var generating = retrieving.recordEvidence(id(107), Set.of(id(108), id(109)), NOW.plusSeconds(2));
        attempts.replace(generating, retrieving.revision());
        var verifying = generating.startVerification(new ModelUsage(50, 20, 3, 400), NOW.plusSeconds(3));
        attempts.replace(verifying, generating.revision());
        var failed = verifying.failRetryable("MODEL_TIMEOUT", NOW.plusSeconds(4));
        attempts.replace(failed, verifying.revision());

        assertThat(new JdbcGenerationAttemptStore(jdbc).findByClientKey(key.clientKey())).contains(failed);
        assertThat(new JdbcGenerationAttemptStore(jdbc).findByClientKey("missing-key")).isEmpty();
    }

    @Test void duplicateRegistrationReturnsOriginalAndRejectsChangedInput() {
        var key = seed(200, "duplicate-client-key");
        var original = GenerationAttempt.start(id(206), key, NOW);
        assertThat(attempts.register(original).created()).isTrue();
        assertThat(attempts.register(GenerationAttempt.start(id(207), key, NOW.plusSeconds(1))))
                .isEqualTo(new GenerationAttemptRegistration(original, false));
        var changed = new GenerationKey(key.clientKey(), key.solutionId(), key.requirementVersionId(),
                key.outlineVersionId(), id(208), Optional.empty());
        assertThatThrownBy(() -> attempts.register(GenerationAttempt.start(id(209), changed, NOW)))
                .isInstanceOf(GenerationRequestConflictException.class);
        assertThat(new JdbcGenerationAttemptStore(jdbc).findByClientKey(key.clientKey())).contains(original);
    }

    @Test void compareAndSwapRejectsStaleWriterAndLeavesCurrentState() {
        var key = seed(300, "stale-writer");
        var started = GenerationAttempt.start(id(306), key, NOW);
        attempts.register(started);
        var retrieving = started.startRetrieval(NOW.plusSeconds(1));
        attempts.replace(retrieving, 0);
        assertThatThrownBy(() -> attempts.replace(started.startRetrieval(NOW.plusSeconds(2)), 0))
                .isInstanceOf(GenerationStateException.class);
        assertThat(new JdbcGenerationAttemptStore(jdbc).findByClientKey(key.clientKey())).contains(retrieving);
    }

    @Test void concurrentRegistrationsSelectOneOwner() throws Exception {
        var key = seed(400, "concurrent-client-key");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> registerAfterBarrier(key, id(406), ready, start));
            var second = pool.submit(() -> registerAfterBarrier(key, id(407), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(GenerationAttemptRegistration::created)).hasSize(1);
            assertThat(results.get(0).attempt()).isEqualTo(results.get(1).attempt());
            assertThat(attempts.findByClientKey(key.clientKey())).contains(results.get(0).attempt());
        }
    }

    @Test void expiresOnlyStalePreVerificationAttemptsInBoundedBatches() {
        var dbNow = jdbc.sql("SELECT statement_timestamp()")
                .query(OffsetDateTime.class).single().toInstant();
        var staleAt = dbNow.minus(Duration.ofMinutes(4));
        var pending = GenerationAttempt.start(id(506), seed(500, "stale-pending"), staleAt);
        attempts.register(pending);
        var retrievingStart = GenerationAttempt.start(id(606), seed(600, "stale-retrieving"), staleAt);
        attempts.register(retrievingStart);
        var retrieving = retrievingStart.startRetrieval(staleAt.plusSeconds(1));
        attempts.replace(retrieving, retrievingStart.revision());
        var generatingStart = GenerationAttempt.start(id(706), seed(700, "stale-generating"), staleAt);
        attempts.register(generatingStart);
        var generatingRetrieval = generatingStart.startRetrieval(staleAt.plusSeconds(1));
        attempts.replace(generatingRetrieval, generatingStart.revision());
        var generating = generatingRetrieval.recordEvidence(id(707), Set.of(id(708)), staleAt.plusSeconds(2));
        attempts.replace(generating, generatingRetrieval.revision());
        var verifyingStart = GenerationAttempt.start(id(806), seed(800, "stale-verifying"), staleAt);
        attempts.register(verifyingStart);
        var verifyingRetrieval = verifyingStart.startRetrieval(staleAt.plusSeconds(1));
        attempts.replace(verifyingRetrieval, verifyingStart.revision());
        var verifyingGenerating = verifyingRetrieval.recordEvidence(id(807), Set.of(id(808)), staleAt.plusSeconds(2));
        attempts.replace(verifyingGenerating, verifyingRetrieval.revision());
        var verifying = verifyingGenerating.startVerification(new ModelUsage(1, 1, 1, 1), staleAt.plusSeconds(3));
        attempts.replace(verifying, verifyingGenerating.revision());
        var fresh = GenerationAttempt.start(id(906), seed(900, "fresh-pending"), dbNow.minusSeconds(20));
        attempts.register(fresh);

        var store = new JdbcGenerationAttemptStore(jdbc);
        for (int scan = 0; scan < 20; scan++) {
            assertThat(store.expireStale(Duration.ofMinutes(2), 2)).isBetween(0, 2);
            if (isExpired(pending) && isExpired(retrieving) && isExpired(generating)) break;
        }
        assertExpired(pending);
        assertExpired(retrieving);
        assertExpired(generating);
        assertThat(attempts.findByClientKey(verifying.key().clientKey())).contains(verifying);
        assertThat(attempts.findByClientKey(fresh.key().clientKey())).contains(fresh);
    }

    @Test void concurrentScannersExpireOneAttemptOnce() throws Exception {
        var dbNow = jdbc.sql("SELECT statement_timestamp()")
                .query(OffsetDateTime.class).single().toInstant();
        var pending = GenerationAttempt.start(id(1006), seed(1000, "concurrent-expiry"),
                dbNow.minus(Duration.ofMinutes(4)));
        attempts.register(pending);
        long eligibleBefore = jdbc.sql("""
                SELECT count(*) FROM solution_generation_attempt
                WHERE status IN ('PENDING', 'RETRIEVING', 'GENERATING')
                  AND updated_at <= statement_timestamp() - INTERVAL '2 minutes'
                """).query(Long.class).single();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> expireAfterBarrier(ready, start));
            var second = pool.submit(() -> expireAfterBarrier(ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS))
                    .isEqualTo(eligibleBefore);
        }
        assertExpired(pending);
    }

    private static int expireAfterBarrier(CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("scanner did not start");
        return new JdbcGenerationAttemptStore(JdbcClient.create(dataSource))
                .expireStale(Duration.ofMinutes(2), 100);
    }

    private static void assertExpired(GenerationAttempt previous) {
        var actual = attempts.findByClientKey(previous.key().clientKey()).orElseThrow();
        assertThat(actual.status()).isEqualTo(GenerationStatus.FAILED_RETRYABLE);
        assertThat(actual.retryCheckpoint()).contains(previous.status());
        assertThat(actual.failureCode()).contains("WORKER_LEASE_EXPIRED");
        assertThat(actual.revision()).isEqualTo(previous.revision() + 1);
        assertThat(actual.executionCount()).isEqualTo(previous.executionCount());
    }

    private static boolean isExpired(GenerationAttempt previous) {
        return attempts.findByClientKey(previous.key().clientKey()).orElseThrow().status()
                == GenerationStatus.FAILED_RETRYABLE;
    }

    @Test void usesDatabaseTimeEvenWhenApplicationClockDiffers() {
        var dbNow = jdbc.sql("SELECT statement_timestamp()")
                .query(OffsetDateTime.class).single().toInstant();
        var store = new JdbcGenerationAttemptStore(jdbc);
        assertThat(store.authoritativeNow(dbNow.minus(Duration.ofHours(2))))
                .isBetween(dbNow.minusSeconds(2), dbNow.plusSeconds(5));
        assertThat(store.authoritativeNow(dbNow.plus(Duration.ofHours(2))))
                .isBetween(dbNow.minusSeconds(2), dbNow.plusSeconds(5));
    }

    private static GenerationAttemptRegistration registerAfterBarrier(GenerationKey key, UUID attemptId,
            CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("concurrent test did not start");
        return new JdbcGenerationAttemptStore(JdbcClient.create(dataSource))
                .register(GenerationAttempt.start(attemptId, key, NOW));
    }

    private static GenerationKey seed(int base, String clientKey) {
        var actor = id(base);
        var space = id(base + 1);
        var solutionId = id(base + 2);
        var requirementId = id(base + 3);
        var outlineId = id(base + 4);
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :external, '测试')")
                .param("id", actor).param("external", actor.toString()).update();
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by) VALUES (:id, '测试空间', :actor)")
                .param("id", space).param("actor", actor).update();
        var requirement = new RequirementVersion(requirementId, 1,
                List.of(new RequirementItem(id(base + 5), RequirementCategory.UNKNOWN, "待明确", List.of())),
                actor, NOW, "初始录入");
        var solution = Solution.create(solutionId, space, actor, SolutionName.of("测试方案"), requirement, NOW);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.executeWithoutResult(status -> {
            new JdbcSolutionRepository(jdbc).insert(solution);
            new JdbcRequirementVersionRepository(jdbc).insert(solutionId, requirement);
            new JdbcOutlineVersionRepository(jdbc).insert(solutionId, new OutlineVersion(outlineId, 1,
                    List.of(new OutlineNode(id(base + 10), "概述", 1, 1, true, "目标", "检索", Set.of())), actor, NOW));
        });
        return new GenerationKey(clientKey, solutionId, requirementId, outlineId, id(base + 10), Optional.empty());
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-7000-8000-%012d".formatted(suffix));
    }
}
