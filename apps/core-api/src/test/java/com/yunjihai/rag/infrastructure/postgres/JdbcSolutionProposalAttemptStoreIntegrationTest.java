package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.proposal.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

class JdbcSolutionProposalAttemptStoreIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_proposal_test").withUsername("migration_owner")
            .withPassword("migration_test_only");
    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");
    private static DriverManagerDataSource dataSource;
    private static DataSourceTransactionManager manager;
    private static JdbcClient jdbc;
    private static JdbcSolutionProposalAttemptStore store;

    @BeforeAll static void start() throws Exception {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_proposal_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()).locations("classpath:db/migration").load().migrate();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                "rag_runtime_test", "runtime_test_only");
        manager = new DataSourceTransactionManager(dataSource);
        jdbc = JdbcClient.create(dataSource);
        store = new JdbcSolutionProposalAttemptStore(jdbc, manager);
    }

    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test void replaysPersistedResultWithoutReclaimingOrOverwritingIt() throws Exception {
        var key = seed(100, ProposalKind.OUTLINE, "outline-key");
        var request = new ProposalRegistrationRequest(key, id(103), 0, "a".repeat(64));

        var first = store.register(request, Duration.ofMinutes(3));
        var second = new JdbcSolutionProposalAttemptStore(jdbc, manager)
                .register(request, Duration.ofMinutes(3));
        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.attempt().id()).isEqualTo(first.attempt().id());
        assertThat(first.attempt().status()).isEqualTo(ProposalStatus.RUNNING);
        assertThat(first.attempt().toString()).doesNotContain("outline-key");

        assertThat(store.succeed(request, "{\"nodes\":[{\"title\":\"总体方案\"}]}"))
                .isTrue();
        var recovered = new JdbcSolutionProposalAttemptStore(jdbc, manager).findByKey(key).orElseThrow();
        assertThat(recovered.status()).isEqualTo(ProposalStatus.SUCCEEDED);
        assertThat(new ObjectMapper().readTree(recovered.resultJson().orElseThrow()))
                .isEqualTo(new ObjectMapper().readTree("{\"nodes\":[{\"title\":\"总体方案\"}]}"));
        assertThat(recovered.toString()).doesNotContain("总体方案");
        assertThat(store.succeed(request, "{\"nodes\":[]}"))
                .isFalse();
        assertThat(store.register(request, Duration.ofMinutes(3)).attempt())
                .isEqualTo(recovered);
        assertThatThrownBy(() -> store.register(new ProposalRegistrationRequest(
                key, id(103), 1, "b".repeat(64)), Duration.ofMinutes(3)))
                .isInstanceOf(ProposalRequestConflictException.class);
    }

    @Test void expiredRunBecomesIndeterminateAndCannotBeRechargedWithTheSameKey() {
        var key = seed(200, ProposalKind.REQUIREMENTS, "requirements-key");
        var request = new ProposalRegistrationRequest(key, id(203), 0, "c".repeat(64));
        var registered = store.register(request, Duration.ofMinutes(3));
        jdbc.sql("UPDATE solution_model_proposal_attempt "
                + "SET lease_until = statement_timestamp() - INTERVAL '1 second' WHERE id = :id")
                .param("id", registered.attempt().id()).update();

        assertThat(store.succeed(request, "{\"items\":[]}"))
                .isFalse();
        assertThat(store.expireStale(10)).isGreaterThanOrEqualTo(1);
        var recovered = new JdbcSolutionProposalAttemptStore(jdbc, manager).register(request,
                Duration.ofMinutes(3));
        assertThat(recovered.created()).isFalse();
        assertThat(recovered.attempt().status()).isEqualTo(ProposalStatus.INDETERMINATE);
        assertThat(recovered.attempt().resultJson()).isEmpty();
    }

    @Test void concurrentRegistrationsCreateExactlyOneModelCallOwner() throws Exception {
        var key = seed(300, ProposalKind.OUTLINE, "concurrent-key");
        var request = new ProposalRegistrationRequest(key, id(303), 0, "d".repeat(64));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> registerAfterBarrier(request, ready, start));
            var second = pool.submit(() -> registerAfterBarrier(request, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var values = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(values.stream().filter(ProposalRegistration::created)).hasSize(1);
            assertThat(values.get(0).attempt().id()).isEqualTo(values.get(1).attempt().id());
        }
    }

    @Test void changedInputCannotMutateAnExpiredOriginalAttempt() {
        var key = seed(400, ProposalKind.OUTLINE, "reused-key");
        var original = new ProposalRegistrationRequest(key, id(403), 0, "e".repeat(64));
        var registered = store.register(original, Duration.ofMinutes(3));
        jdbc.sql("UPDATE solution_model_proposal_attempt "
                + "SET lease_until = statement_timestamp() - INTERVAL '1 second' WHERE id = :id")
                .param("id", registered.attempt().id()).update();

        assertThatThrownBy(() -> store.register(new ProposalRegistrationRequest(
                key, id(403), 1, "f".repeat(64)), Duration.ofMinutes(3)))
                .isInstanceOf(ProposalRequestConflictException.class);
        assertThat(store.findByKey(key).orElseThrow().status()).isEqualTo(ProposalStatus.RUNNING);
        assertThat(store.register(original, Duration.ofMinutes(3)).attempt().status())
                .isEqualTo(ProposalStatus.INDETERMINATE);
    }

    @Test void completionWaitingOnRowLockCannotCommitAfterLeaseExpiry() throws Exception {
        var key = seed(500, ProposalKind.OUTLINE, "locked-completion-key");
        var request = new ProposalRegistrationRequest(key, id(503), 0, "a".repeat(64));
        var registered = store.register(request, Duration.ofSeconds(3));
        try (var lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement(
                    "SELECT id FROM solution_model_proposal_attempt WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, registered.attempt().id());
                statement.executeQuery().close();
            }
            try (var pool = Executors.newSingleThreadExecutor()) {
                var completion = pool.submit(() -> new JdbcSolutionProposalAttemptStore(
                        JdbcClient.create(dataSource), manager).succeed(request, "{\"nodes\":[]}"));
                try {
                    assertThat(awaitCondition(() -> jdbc.sql("""
                            SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                              WHERE datname = current_database() AND usename = current_user
                                AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'
                                AND query LIKE '%solution_model_proposal_attempt%'
                                AND query LIKE '%FOR UPDATE%')
                            """).query(Boolean.class).single(), Duration.ofSeconds(2)))
                            .as("completion must be blocked on the proposal row before expiry")
                            .isTrue();
                    assertThat(jdbc.sql("SELECT lease_until > clock_timestamp() "
                            + "FROM solution_model_proposal_attempt WHERE id = :id")
                            .param("id", registered.attempt().id()).query(Boolean.class).single())
                            .as("the lease must still be active when lock contention is observed")
                            .isTrue();
                    assertThat(awaitCondition(() -> jdbc.sql("SELECT lease_until <= clock_timestamp() "
                            + "FROM solution_model_proposal_attempt WHERE id = :id")
                            .param("id", registered.attempt().id()).query(Boolean.class).single(),
                            Duration.ofSeconds(5))).isTrue();
                } finally {
                    lock.commit();
                }
                assertThat(completion.get(5, TimeUnit.SECONDS)).isFalse();
            }
        }
        assertThat(store.findByKey(key).orElseThrow().status()).isEqualTo(ProposalStatus.RUNNING);
    }

    private static boolean awaitCondition(Supplier<Boolean> condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (Boolean.TRUE.equals(condition.get())) return true;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private static ProposalRegistration registerAfterBarrier(ProposalRegistrationRequest request,
            CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start barrier timed out");
        return new JdbcSolutionProposalAttemptStore(JdbcClient.create(dataSource), manager)
                .register(request, Duration.ofMinutes(3));
    }

    private static ProposalKey seed(int base, ProposalKind kind, String clientKey) {
        var actor = id(base);
        var space = id(base + 1);
        var solutionId = id(base + 2);
        var requirementId = id(base + 3);
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :external, '测试')")
                .param("id", actor).param("external", actor.toString()).update();
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by) VALUES (:id, '测试空间', :actor)")
                .param("id", space).param("actor", actor).update();
        var requirement = new RequirementVersion(requirementId, 1,
                List.of(new RequirementItem(id(base + 5), RequirementCategory.UNKNOWN,
                        "待明确", List.of())), actor, NOW, "初始录入");
        var solution = Solution.create(solutionId, space, actor,
                SolutionName.of("测试方案"), requirement, NOW);
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
            new JdbcSolutionRepository(jdbc).insert(solution);
            new JdbcRequirementVersionRepository(jdbc).insert(solutionId, requirement);
        });
        return new ProposalKey(actor, solutionId, kind, clientKey);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-7000-8000-%012d".formatted(suffix));
    }
}
