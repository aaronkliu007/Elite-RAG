package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.access.AclCommandRepository;
import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.access.PrincipalType;
import com.yunjihai.rag.access.role.RoleAssignmentService;
import com.yunjihai.rag.access.role.RoleAssignmentStore;
import com.yunjihai.rag.access.role.SpaceRole;
import com.yunjihai.rag.access.role.SystemRole;
import com.yunjihai.rag.audit.AuditEventStore;
import com.yunjihai.rag.audit.AuditService;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import com.yunjihai.rag.retrieval.projection.ProjectionChangePublisher;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcRoleAssignmentIntegrationTest {
    private static final Instant AT = Instant.parse("2026-10-08T06:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID OTHER_USER_ID = UUID.fromString("00000000-0000-7000-8000-000000000003");
    private static final UUID SPACE_ID = UUID.fromString("00000000-0000-7000-8000-000000000100");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_role_test")
            .withUsername("migration_owner")
            .withPassword("migration_test_only");

    private static AnnotationConfigApplicationContext context;
    private static JdbcClient jdbc;
    private static JdbcClient ownerJdbc;
    private static JdbcRoleAssignmentStore store;
    private static RoleAssignmentService roles;
    private static TestProjectionPublisher projections;

    @BeforeAll
    static void startDatabase() throws Exception {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_role_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        ownerJdbc = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        TestConfig.runtimeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only");
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = context.getBean(JdbcClient.class);
        store = context.getBean(JdbcRoleAssignmentStore.class);
        roles = context.getBean(RoleAssignmentService.class);
        projections = context.getBean(TestProjectionPublisher.class);
    }

    @BeforeEach
    void seed() {
        insertUser(ACTOR_ID, "actor");
        insertUser(USER_ID, "member");
        insertUser(OTHER_USER_ID, "other");
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by, created_at) VALUES (:id, '研发', :actor, :at)")
                .param("id", SPACE_ID).param("actor", ACTOR_ID).param("at", timestamp(AT)).update();
        projections.fail = false;
        projections.changeCount = 0;
    }

    @AfterEach
    void clearDatabase() {
        ownerJdbc.sql("DELETE FROM audit_event").update();
        jdbc.sql("DELETE FROM acl_entry").update();
        jdbc.sql("DELETE FROM space_role_assignment").update();
        jdbc.sql("DELETE FROM system_role_assignment").update();
        jdbc.sql("DELETE FROM knowledge_space").update();
        jdbc.sql("DELETE FROM app_user").update();
    }

    @AfterAll
    static void stopDatabase() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @Test
    void storesFixedSystemRolesAndIgnoresDisabledAdministrators() {
        store.grantSystemRole(USER_ID, SystemRole.SYSTEM_ADMIN, ACTOR_ID, AT);
        store.grantSystemRole(USER_ID, SystemRole.SYSTEM_ADMIN, ACTOR_ID, AT.plusSeconds(1));

        assertThat(store.hasSystemRole(USER_ID, SystemRole.SYSTEM_ADMIN)).isTrue();
        assertThat(store.hasAnySystemRole(SystemRole.SYSTEM_ADMIN)).isTrue();
        assertThat(count("system_role_assignment", "revoked_at IS NULL")).isEqualTo(1);

        jdbc.sql("UPDATE app_user SET enabled = false WHERE id = :id").param("id", USER_ID).update();
        assertThat(store.hasSystemRole(USER_ID, SystemRole.SYSTEM_ADMIN)).isFalse();
        assertThat(store.hasAnySystemRole(SystemRole.SYSTEM_ADMIN)).isFalse();
    }

    @Test
    void firstSpaceRoleCreatesAclAndLastRevocationRemovesIt() {
        roles.grantSpaceRole(ACTOR_ID, SPACE_ID, USER_ID, SpaceRole.EDITOR, AT);
        roles.grantSpaceRole(ACTOR_ID, SPACE_ID, USER_ID, SpaceRole.READER, AT.plusSeconds(1));

        assertThat(roles.activeSpaceRoles(USER_ID, SPACE_ID))
                .containsExactlyInAnyOrder(SpaceRole.EDITOR, SpaceRole.READER);
        assertThat(activeUserAclCount(USER_ID)).isEqualTo(1);
        assertThat(count("audit_event", "action = 'ROLE_CHANGE'")).isEqualTo(2);
        assertThat(projections.changeCount).isEqualTo(2);

        roles.revokeSpaceRole(ACTOR_ID, SPACE_ID, USER_ID, SpaceRole.EDITOR, AT.plusSeconds(2));
        assertThat(activeUserAclCount(USER_ID)).isEqualTo(1);
        roles.revokeSpaceRole(ACTOR_ID, SPACE_ID, USER_ID, SpaceRole.READER, AT.plusSeconds(3));

        assertThat(roles.activeSpaceRoles(USER_ID, SPACE_ID)).isEmpty();
        assertThat(activeUserAclCount(USER_ID)).isZero();
        assertThat(count("acl_entry", "principal_id = '" + USER_ID + "' AND revoked_at IS NOT NULL"))
                .isEqualTo(1);
    }

    @Test
    void projectionFailureRollsBackRoleAclAndAuditTogether() {
        projections.fail = true;

        assertThatThrownBy(() -> roles.grantSpaceRole(
                ACTOR_ID, SPACE_ID, OTHER_USER_ID, SpaceRole.EDITOR, AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("projection failed");

        assertThat(store.activeSpaceRoles(OTHER_USER_ID, SPACE_ID)).isEmpty();
        assertThat(activeUserAclCount(OTHER_USER_ID)).isZero();
        assertThat(count("audit_event", "resource_id = '" + SPACE_ID + "'"))
                .isZero();
    }

    private static long activeUserAclCount(UUID userId) {
        return jdbc.sql("""
                        SELECT count(*) FROM acl_entry
                        WHERE scope = 'SPACE' AND space_id = :space
                          AND principal_type = 'USER' AND principal_id = :user
                          AND revoked_at IS NULL
                        """)
                .param("space", SPACE_ID).param("user", userId).query(Long.class).single();
    }

    private static long count(String table, String predicate) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + predicate)
                .query(Long.class).single();
    }

    private static void insertUser(UUID id, String externalId) {
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :external, :name)")
                .param("id", id).param("external", externalId).param("name", externalId).update();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
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
        @Bean JdbcRoleAssignmentStore roleStore(JdbcClient jdbc) { return new JdbcRoleAssignmentStore(jdbc); }
        @Bean JdbcAclCommandRepository acl(JdbcClient jdbc) { return new JdbcAclCommandRepository(jdbc); }
        @Bean JdbcAuditEventStore auditStore(JdbcClient jdbc) { return new JdbcAuditEventStore(jdbc); }
        @Bean AuditService audit(AuditEventStore store) { return new AuditService(store, Clock.fixed(AT, ZoneOffset.UTC)); }
        @Bean TestProjectionPublisher projections() { return new TestProjectionPublisher(); }
        @Bean RoleAssignmentService roles(
                RoleAssignmentStore store,
                AclCommandRepository acl,
                AuditService audit,
                TestProjectionPublisher projections) {
            return new RoleAssignmentService(
                    store, acl, audit, projections, KnowledgeWriteAuthorization.allowAll());
        }
    }

    static final class TestProjectionPublisher implements ProjectionChangePublisher {
        private boolean fail;
        private int changeCount;

        @Override public <T> T withSpaceChangeLock(UUID spaceId, Supplier<T> action) { return action.get(); }
        @Override public void documentChanged(com.yunjihai.rag.knowledge.DocumentAggregate document, UUID versionId, Instant at) { }
        @Override public void documentAclChanged(UUID spaceId, UUID documentId, Instant at) { }
        @Override public void spaceAclChanged(UUID spaceId, Instant at) {
            if (fail) throw new IllegalStateException("projection failed");
            changeCount++;
        }
    }
}
