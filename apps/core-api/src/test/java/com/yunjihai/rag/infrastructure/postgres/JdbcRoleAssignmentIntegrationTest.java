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
        jdbc.sql("DELETE FROM org_membership").update();
        jdbc.sql("DELETE FROM org_unit").update();
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
    void directAndRoleGrantsRemainIndependentWhenEitherIsRevoked() {
        var acl = context.getBean(JdbcAclCommandRepository.class);
        var principal = new PrincipalRef(PrincipalType.USER, USER_ID);
        acl.grantSpace(SPACE_ID, principal, AT.plusSeconds(3600), AT);
        roles.grantSpaceRole(ACTOR_ID, SPACE_ID, USER_ID, SpaceRole.READER, AT.plusSeconds(1));
        assertThat(activeUserAclCount(USER_ID)).isEqualTo(2);
        roles.revokeSpaceRole(ACTOR_ID, SPACE_ID, USER_ID, SpaceRole.READER, AT.plusSeconds(2));
        assertThat(acl.isWithinSpaceScope(SPACE_ID, principal, AT.plusSeconds(3))).isTrue();
        assertThat(activeUserAclCount(USER_ID)).isEqualTo(1);
        roles.grantSpaceRole(ACTOR_ID, SPACE_ID, USER_ID, SpaceRole.READER, AT.plusSeconds(4));
        acl.revokeSpace(SPACE_ID, principal, AT.plusSeconds(5));
        assertThat(acl.isWithinSpaceScope(SPACE_ID, principal, AT.plusSeconds(6))).isTrue();
        assertThat(activeUserAclCount(USER_ID)).isEqualTo(1);
    }

    @Test
    void legacyGrantIsRetainedForReviewWhenRoleIsRevoked() {
        jdbc.sql("""
                INSERT INTO acl_entry (id, space_id, scope, principal_type, principal_id, grant_source)
                VALUES (:id, :space, 'SPACE', 'USER', :user, 'LEGACY_UNKNOWN')
                """).param("id", UUID.randomUUID()).param("space", SPACE_ID).param("user", USER_ID).update();
        roles.grantSpaceRole(ACTOR_ID, SPACE_ID, USER_ID, SpaceRole.READER, AT);
        roles.revokeSpaceRole(ACTOR_ID, SPACE_ID, USER_ID, SpaceRole.READER, AT.plusSeconds(1));
        assertThat(activeUserAclCount(USER_ID)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT grant_source FROM acl_entry WHERE principal_id = :user AND revoked_at IS NULL")
                .param("user", USER_ID).query(String.class).single()).isEqualTo("LEGACY_UNKNOWN");
    }

    @Test
    void permissionOverviewReturnsSourcesIndividually() {
        context.getBean(JdbcAclCommandRepository.class).grantSpace(SPACE_ID, new PrincipalRef(PrincipalType.USER, USER_ID), null, AT);
        roles.grantSpaceRole(ACTOR_ID, SPACE_ID, USER_ID, SpaceRole.READER, AT);
        var grants = new JdbcPermissionGrantQuery(jdbc).listForSpace(SPACE_ID, 0, 100);
        assertThat(grants).extracting(com.yunjihai.rag.access.PermissionGrantQuery.Grant::source)
                .containsExactlyInAnyOrder("DIRECT", "SPACE_ROLE");
    }

    @Test
    void effectivePermissionsIncludeActiveAncestorDepartmentAndRespectExpiryAndDisabledAccount() {
        UUID parent = UUID.randomUUID(), child = UUID.randomUUID();
        jdbc.sql("INSERT INTO local_credential (user_id, username_normalized, password_hash) VALUES (:u, 'member', 'test-hash')")
                .param("u", USER_ID).update();
        jdbc.sql("INSERT INTO org_unit (id, external_id, name) VALUES (:id, 'parent', '上级部门')").param("id", parent).update();
        jdbc.sql("INSERT INTO org_unit (id, external_id, name, parent_id) VALUES (:id, 'child', '子部门', :parent)")
                .param("id", child).param("parent", parent).update();
        jdbc.sql("INSERT INTO org_membership (user_id, org_unit_id, snapshot_at) VALUES (:u, :d, :at)")
                .param("u", USER_ID).param("d", child).param("at", timestamp(AT)).update();
        var acl = context.getBean(JdbcAclCommandRepository.class);
        acl.grantSpace(SPACE_ID, new PrincipalRef(PrincipalType.DEPARTMENT, parent), AT.plusSeconds(30), AT);
        var service = new com.yunjihai.rag.access.EffectiveUserPermissionService(new JdbcPermissionGrantQuery(jdbc),
                new com.yunjihai.rag.access.EffectivePrincipalSet(new JdbcPrincipalMembershipRepository(jdbc)),
                new JdbcUserAccountStore(jdbc), store, Clock.fixed(AT.plusSeconds(1), ZoneOffset.UTC));
        var result = service.forUser(SPACE_ID, USER_ID);
        assertThat(result.spaceReadable()).isTrue();
        assertThat(result.grants()).singleElement().satisfies(g -> {
            assertThat(g.inherited()).isTrue(); assertThat(g.grant().displayName()).isEqualTo("上级部门");
        });
        jdbc.sql("UPDATE app_user SET enabled=false WHERE id=:u").param("u", USER_ID).update();
        assertThat(service.forUser(SPACE_ID, USER_ID).spaceReadable()).isFalse();
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
