package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.identity.ExternalDepartment;
import com.yunjihai.rag.identity.ExternalUser;
import com.yunjihai.rag.identity.IncompleteOrganizationSnapshotException;
import com.yunjihai.rag.identity.InvalidOrganizationSnapshotException;
import com.yunjihai.rag.identity.OrganizationSnapshot;
import com.yunjihai.rag.identity.OrganizationSyncService;
import com.yunjihai.rag.identity.SessionRevocationService;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JdbcOrganizationIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_organization_test").withUsername("migration_owner").withPassword("migration_test_only");
    private static final Instant CAPTURED = Instant.parse("2026-09-29T08:00:00Z");
    private static DataSource runtimeDataSource;
    private static AnnotationConfigApplicationContext context;
    private static JdbcClient jdbc;
    private static JdbcOrganizationReconciliationAdapter organizations;
    private static JdbcSessionVersionStore versions;
    private static OrganizationSyncService service;
    private static PlatformTransactionManager transactions;

    @BeforeAll
    static void setUp() throws SQLException {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_organization_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        runtimeDataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only");
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = context.getBean(JdbcClient.class);
        organizations = context.getBean(JdbcOrganizationReconciliationAdapter.class);
        versions = context.getBean(JdbcSessionVersionStore.class);
        service = context.getBean(OrganizationSyncService.class);
        transactions = context.getBean(PlatformTransactionManager.class);
    }

    @AfterEach
    void clearRows() {
        jdbc.sql("DELETE FROM org_membership").update();
        jdbc.sql("DELETE FROM org_unit").update();
        jdbc.sql("DELETE FROM app_user").update();
    }

    @AfterAll
    static void tearDown() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @Test
    void completeSnapshotAtomicallyUpsertsUsersDepartmentsAndMemberships() {
        service.synchronize(snapshot(List.of(department("root", null), department("team", "root")),
                List.of(user("alice", true, "team"))));
        UUID userId = id("app_user", "alice");
        UUID rootId = id("org_unit", "root");
        UUID teamId = id("org_unit", "team");
        assertEquals(7, userId.version());
        assertEquals(7, rootId.version());
        assertEquals(7, teamId.version());
        assertEquals(rootId, jdbc.sql("SELECT parent_id FROM org_unit WHERE external_id = 'team'").query(UUID.class).single());
        assertEquals(1L, count("org_membership"));
        assertEquals(teamId, jdbc.sql("SELECT org_unit_id FROM org_membership WHERE user_id = :id")
                .param("id", userId).query(UUID.class).single());

        service.synchronize(snapshot(List.of(new ExternalDepartment("root", null, "Renamed"), department("team", "root")),
                List.of(new ExternalUser("alice", "Alice Renamed", true, Set.of("root")))));
        assertEquals(userId, id("app_user", "alice"));
        assertEquals(rootId, id("org_unit", "root"));
        assertEquals(teamId, id("org_unit", "team"));
        assertEquals("Renamed", jdbc.sql("SELECT name FROM org_unit WHERE external_id = 'root'").query(String.class).single());
        assertEquals(rootId, jdbc.sql("SELECT org_unit_id FROM org_membership WHERE user_id = :id")
                .param("id", userId).query(UUID.class).single());
        assertEquals(1L, count("org_membership"));
    }

    @Test
    void removedOrDisabledUsersAreDisabledAndTheirSessionVersionAdvances() {
        service.synchronize(snapshot(List.of(department("root", null)),
                List.of(user("removed", true, "root"), user("disabled", true, "root"))));
        service.synchronize(snapshot(List.of(department("root", null)),
                List.of(user("disabled", false, "root"), user("new-inactive", false, "root"))));
        assertFalse(versions.isEnabled("removed"));
        assertFalse(versions.isEnabled("disabled"));
        assertEquals(1, versions.currentVersion("removed"));
        assertEquals(1, versions.currentVersion("disabled"));
        assertFalse(versions.isEnabled("new-inactive"));
        assertEquals(0, versions.currentVersion("new-inactive"));
        assertEquals(0L, count("org_membership"));
        service.synchronize(snapshot(List.of(department("root", null)), List.of(user("disabled", false, "root"))));
        assertEquals(1, versions.currentVersion("removed"));
        assertEquals(1, versions.currentVersion("disabled"));
    }

    @Test
    void incompleteSnapshotLeavesPreviousRowsUntouched() {
        service.synchronize(snapshot(List.of(department("root", null)), List.of(user("alice", true, "root"))));
        assertThrows(IncompleteOrganizationSnapshotException.class, () -> organizations.reconcile(
                new OrganizationSnapshot(List.of(), List.of(), CAPTURED, false)));
        assertEquals(1L, count("org_unit"));
        assertEquals(1L, count("app_user"));
        assertEquals(1L, count("org_membership"));
    }

    @Test
    void blankOversizedOrDuplicateExternalIdsRejectTheWholeSnapshot() {
        service.synchronize(snapshot(List.of(department("root", null)), List.of(user("alice", true, "root"))));
        List<OrganizationSnapshot> invalid = List.of(
                snapshot(List.of(department("  ", null)), List.of()),
                snapshot(List.of(department("root", null)), List.of(user(" ", true, "root"))),
                snapshot(List.of(department("x".repeat(256), null)), List.of()),
                snapshot(List.of(department("root", null)), List.of(user("u".repeat(256), true, "root"))),
                snapshot(List.of(department("dup", null), department("dup", null)), List.of()),
                snapshot(List.of(department("root", null)), List.of(user("dup", true, "root"), user("dup", true, "root"))));
        for (var bad : invalid) {
            assertThrows(InvalidOrganizationSnapshotException.class, () -> organizations.reconcile(bad));
            assertEquals(1L, count("app_user"));
            assertEquals(1L, count("org_unit"));
            assertEquals(1L, count("org_membership"));
        }
    }

    @Test
    void departmentParentCycleRejectsTheWholeSnapshot() {
        service.synchronize(snapshot(List.of(department("root", null)), List.of(user("alice", true, "root"))));
        assertThrows(InvalidOrganizationSnapshotException.class, () -> organizations.reconcile(
                snapshot(List.of(department("a", "b"), department("b", "a")), List.of())));
        assertEquals("root", jdbc.sql("SELECT external_id FROM org_unit").query(String.class).single());
        assertEquals(1L, count("org_membership"));
    }

    @Test
    void sessionDisableFailureRollsBackReconciliationAndVersionAdvance() {
        service.synchronize(snapshot(List.of(department("root", null)), List.of(user("alice", true, "root"))));
        jdbc.sql("UPDATE app_user SET session_version = :max WHERE external_id = 'alice'")
                .param("max", Long.MAX_VALUE).update();
        var failure = assertThrows(PostgresPersistenceExceptionTranslator.Failure.class, () -> service.synchronize(
                snapshot(List.of(new ExternalDepartment("root", null, "Changed"), department("new", "root")),
                        List.of(user("alice", false, "new")))));
        assertEquals("DATABASE_FAILURE", failure.code());
        assertEquals("DATABASE_FAILURE", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals("root", jdbc.sql("SELECT name FROM org_unit WHERE external_id = 'root'").query(String.class).single());
        assertEquals(1L, count("org_unit"));
        assertTrue(versions.isEnabled("alice"));
        assertEquals(Long.MAX_VALUE, versions.currentVersion("alice"));
        assertEquals(1L, count("org_membership"));
    }

    @Test
    void reconciliationSerializesConcurrentSnapshotsUntilTransactionEnds() {
        var contender = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only"));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            organizations.reconcile(snapshot(List.of(department("root", null)), List.of()));
            assertFalse(contender.sql("SELECT pg_try_advisory_xact_lock(20260929, 3)")
                    .query(Boolean.class).single());
        });
        assertTrue(contender.sql("SELECT pg_try_advisory_xact_lock(20260929, 3)")
                .query(Boolean.class).single());
    }

    private static OrganizationSnapshot snapshot(List<ExternalDepartment> departments, List<ExternalUser> users) {
        return new OrganizationSnapshot(departments, users, CAPTURED, true);
    }

    private static ExternalDepartment department(String id, String parent) {
        return new ExternalDepartment(id, parent, id);
    }

    private static ExternalUser user(String id, boolean active, String department) {
        return new ExternalUser(id, id, active, Set.of(department));
    }

    private static UUID id(String table, String externalId) {
        return jdbc.sql("SELECT id FROM " + table + " WHERE external_id = :externalId")
                .param("externalId", externalId).query(UUID.class).single();
    }

    private static long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfig {
        @Bean DataSource dataSource() { return runtimeDataSource; }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) { return new DataSourceTransactionManager(dataSource); }
        @Bean JdbcClient jdbcClient(DataSource dataSource) { return JdbcClient.create(dataSource); }
        @Bean JdbcOrganizationReconciliationAdapter organizations(JdbcClient jdbc) { return new JdbcOrganizationReconciliationAdapter(jdbc); }
        @Bean JdbcSessionVersionStore versions(JdbcClient jdbc) { return new JdbcSessionVersionStore(jdbc); }
        @Bean OrganizationSyncService service(JdbcOrganizationReconciliationAdapter organizations, JdbcSessionVersionStore versions) {
            return new OrganizationSyncService(organizations, new SessionRevocationService(versions));
        }
    }
}
