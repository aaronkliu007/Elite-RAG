package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.access.AccessDecisionService;
import com.yunjihai.rag.access.AclCommandRepository;
import com.yunjihai.rag.access.AclService;
import com.yunjihai.rag.access.EffectivePrincipalSet;
import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.access.PrincipalType;
import com.yunjihai.rag.audit.AuditEvent;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcAclIntegrationTest {
    private static final Instant AT = Instant.parse("2026-09-29T08:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID LEAF_ID = UUID.fromString("00000000-0000-7000-8000-000000000010");
    private static final UUID PARENT_ID = UUID.fromString("00000000-0000-7000-8000-000000000011");
    private static final UUID ROOT_ID = UUID.fromString("00000000-0000-7000-8000-000000000012");
    private static final UUID SPACE_ID = UUID.fromString("00000000-0000-7000-8000-000000000100");
    private static final UUID PUBLISHED_ID = UUID.fromString("00000000-0000-7000-8000-000000000200");
    private static final UUID DRAFT_ID = UUID.fromString("00000000-0000-7000-8000-000000000201");
    private static final UUID ARCHIVED_ID = UUID.fromString("00000000-0000-7000-8000-000000000202");
    private static final UUID FIXED_AUDIT_ID = UUID.fromString("00000000-0000-7000-8000-000000000300");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_acl_test")
            .withUsername("migration_owner")
            .withPassword("migration_test_only");

    private static AnnotationConfigApplicationContext context;
    private static JdbcClient jdbc;
    private static JdbcClient ownerJdbc;
    private static JdbcPrincipalMembershipRepository memberships;
    private static JdbcAccessDecisionRepository decisions;
    private static JdbcAclCommandRepository commands;
    private static AclService aclService;
    private static AccessDecisionService access;

    @BeforeAll
    static void startDatabase() throws SQLException {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_acl_test SET rag.runtime_role = 'rag_runtime_test'");
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
        memberships = context.getBean(JdbcPrincipalMembershipRepository.class);
        decisions = context.getBean(JdbcAccessDecisionRepository.class);
        commands = context.getBean(JdbcAclCommandRepository.class);
        aclService = context.getBean(AclService.class);
        access = new AccessDecisionService(new EffectivePrincipalSet(memberships), decisions);
    }

    @BeforeEach
    void seedOrganizationAndDocuments() {
        insertUser(ACTOR_ID, "actor");
        insertUser(USER_ID, "reader");
        insertDepartment(ROOT_ID, null, "Root");
        insertDepartment(PARENT_ID, ROOT_ID, "Parent");
        insertDepartment(LEAF_ID, PARENT_ID, "Leaf");
        jdbc.sql("INSERT INTO org_membership (user_id, org_unit_id, snapshot_at) VALUES (:user, :department, :at)")
                .param("user", USER_ID).param("department", LEAF_ID).param("at", timestamp(AT)).update();
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by, created_at) VALUES (:id, 'Engineering', :actor, :at)")
                .param("id", SPACE_ID).param("actor", ACTOR_ID).param("at", timestamp(AT)).update();
        insertDocument(PUBLISHED_ID, false, "PUBLISHED", true);
        insertDocument(DRAFT_ID, false, "DRAFT", false);
        insertDocument(ARCHIVED_ID, true, "PUBLISHED", true);
    }

    @AfterEach
    void clearDatabase() {
        ownerJdbc.sql("DELETE FROM audit_event").update();
        jdbc.sql("DELETE FROM acl_entry").update();
        jdbc.sql("UPDATE document SET current_published_version_id = NULL").update();
        jdbc.sql("DELETE FROM document_version").update();
        jdbc.sql("DELETE FROM document").update();
        jdbc.sql("DELETE FROM knowledge_space").update();
        jdbc.sql("DELETE FROM org_membership").update();
        jdbc.sql("DELETE FROM org_unit").update();
        jdbc.sql("DELETE FROM app_user").update();
    }

    @AfterAll
    static void stopDatabase() {
        if (context != null) {
            context.close();
        }
        POSTGRES.stop();
    }

    @Test
    void loadsOnlyCurrentUserAndAncestorDepartmentsWithoutInventingUnsupportedMemberships() {
        var snapshot = memberships.findByUserId(USER_ID);

        assertEquals(Set.of(LEAF_ID), snapshot.departmentIds());
        assertEquals(PARENT_ID, snapshot.parentDepartmentByChild().get(LEAF_ID));
        assertEquals(ROOT_ID, snapshot.parentDepartmentByChild().get(PARENT_ID));
        assertTrue(snapshot.roleIds().isEmpty());
        assertTrue(snapshot.projectGroupIds().isEmpty());
        assertEquals(Set.of(
                        new PrincipalRef(PrincipalType.USER, USER_ID),
                        new PrincipalRef(PrincipalType.DEPARTMENT, LEAF_ID),
                        new PrincipalRef(PrincipalType.DEPARTMENT, PARENT_ID),
                        new PrincipalRef(PrincipalType.DEPARTMENT, ROOT_ID)),
                new EffectivePrincipalSet(memberships).forUser(USER_ID));

        jdbc.sql("UPDATE org_unit SET enabled = false WHERE id = :id").param("id", PARENT_ID).update();
        assertEquals(Set.of(
                        new PrincipalRef(PrincipalType.USER, USER_ID),
                        new PrincipalRef(PrincipalType.DEPARTMENT, LEAF_ID)),
                new EffectivePrincipalSet(memberships).forUser(USER_ID));
    }

    @Test
    void deniesByDefaultAndStopsUsingDepartmentGrantAfterMembershipIsRemoved() {
        assertFalse(access.canRead(USER_ID, SPACE_ID, PUBLISHED_ID, AT));
        commands.grantSpace(SPACE_ID, new PrincipalRef(PrincipalType.DEPARTMENT, ROOT_ID), null, AT);

        assertTrue(access.canRead(USER_ID, SPACE_ID, PUBLISHED_ID, AT));
        jdbc.sql("DELETE FROM org_membership WHERE user_id = :user").param("user", USER_ID).update();
        assertFalse(access.canRead(USER_ID, SPACE_ID, PUBLISHED_ID, AT));
    }

    @Test
    void documentRestrictionRequiresAnActiveGrantIntersectingTheEffectivePrincipals() {
        var leaf = new PrincipalRef(PrincipalType.DEPARTMENT, LEAF_ID);
        var root = new PrincipalRef(PrincipalType.DEPARTMENT, ROOT_ID);
        commands.grantSpace(SPACE_ID, root, null, AT);
        commands.grantDocument(SPACE_ID, PUBLISHED_ID,
                new PrincipalRef(PrincipalType.USER, ACTOR_ID), null, AT);

        assertFalse(access.canRead(USER_ID, SPACE_ID, PUBLISHED_ID, AT));
        commands.grantDocument(SPACE_ID, PUBLISHED_ID, leaf, null, AT);
        assertTrue(access.canRead(USER_ID, SPACE_ID, PUBLISHED_ID, AT));
        commands.revokeDocument(SPACE_ID, PUBLISHED_ID, leaf, AT.plusSeconds(1));
        assertFalse(access.canRead(USER_ID, SPACE_ID, PUBLISHED_ID, AT.plusSeconds(1)));
        assertEquals(2L, aclCount("DOCUMENT", PUBLISHED_ID));
        assertEquals(1L, jdbc.sql("""
                        SELECT count(*) FROM acl_entry
                        WHERE scope = 'DOCUMENT' AND document_id = :document AND revoked_at IS NOT NULL
                        """).param("document", PUBLISHED_ID).query(Long.class).single());
    }

    @Test
    void revokedAndExactlyExpiredGrantsDenyWithoutRemovingAclHistory() {
        var user = new PrincipalRef(PrincipalType.USER, USER_ID);
        commands.grantSpace(SPACE_ID, user, AT, AT.minusSeconds(30));
        assertTrue(access.canRead(USER_ID, SPACE_ID, PUBLISHED_ID, AT.minusNanos(1_000)));
        assertFalse(access.canRead(USER_ID, SPACE_ID, PUBLISHED_ID, AT));

        commands.grantSpace(SPACE_ID, user, null, AT.plusSeconds(1));
        assertTrue(access.canRead(USER_ID, SPACE_ID, PUBLISHED_ID, AT.plusSeconds(1)));
        commands.revokeSpace(SPACE_ID, user, AT.plusSeconds(2));
        assertFalse(access.canRead(USER_ID, SPACE_ID, PUBLISHED_ID, AT.plusSeconds(2)));
        assertEquals(1L, aclCount("SPACE", null));
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM acl_entry WHERE scope = 'SPACE' AND revoked_at IS NOT NULL")
                .query(Long.class).single());
    }

    @Test
    void unpublishedAndArchivedDocumentsRemainDeniedDespiteSpaceGrant() {
        commands.grantSpace(SPACE_ID, new PrincipalRef(PrincipalType.USER, USER_ID), null, AT);

        assertFalse(access.canRead(USER_ID, SPACE_ID, DRAFT_ID, AT));
        assertFalse(access.canRead(USER_ID, SPACE_ID, ARCHIVED_ID, AT));
        assertTrue(access.canRead(USER_ID, SPACE_ID, PUBLISHED_ID, AT));
    }

    @Test
    void duplicateActiveGrantIsIdempotentAndUpdatesItsExpiry() {
        var user = new PrincipalRef(PrincipalType.USER, USER_ID);
        commands.grantSpace(SPACE_ID, user, AT.plusSeconds(10), AT);
        commands.grantSpace(SPACE_ID, user, AT.plusSeconds(20), AT.plusSeconds(1));

        assertEquals(1L, aclCount("SPACE", null));
        assertEquals(AT.plusSeconds(20), jdbc.sql("""
                        SELECT expires_at FROM acl_entry
                        WHERE scope = 'SPACE' AND principal_id = :principal AND revoked_at IS NULL
                        """).param("principal", USER_ID).query(OffsetDateTime.class).single().toInstant());
    }

    @Test
    void auditAppendFailureRollsBackAclMutationAndDoesNotLeakDatabaseDetails() {
        var user = new PrincipalRef(PrincipalType.USER, USER_ID);
        aclService.grantSpace(ACTOR_ID, SPACE_ID, user, AT.plusSeconds(10), AT);

        var failure = assertThrows(PostgresPersistenceExceptionTranslator.Failure.class,
                () -> aclService.grantSpace(ACTOR_ID, SPACE_ID, user, AT.plusSeconds(20), AT.plusSeconds(1)));

        assertEquals("UNIQUE_CONSTRAINT", failure.code());
        assertEquals("UNIQUE_CONSTRAINT", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(1L, aclCount("SPACE", null));
        assertEquals(AT.plusSeconds(10), jdbc.sql("""
                        SELECT expires_at FROM acl_entry
                        WHERE scope = 'SPACE' AND principal_id = :principal AND revoked_at IS NULL
                        """).param("principal", USER_ID).query(OffsetDateTime.class).single().toInstant());
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM audit_event").query(Long.class).single());
    }

    @Test
    void invalidDocumentScopeUsesStableFailureWithoutSqlOrConstraintDetails() {
        UUID missingDocument = UUID.fromString("00000000-0000-7000-8000-000000000299");
        var failure = assertThrows(PostgresPersistenceExceptionTranslator.Failure.class,
                () -> commands.grantDocument(SPACE_ID, missingDocument,
                        new PrincipalRef(PrincipalType.USER, USER_ID), null, AT));

        assertEquals("FOREIGN_KEY_CONSTRAINT", failure.code());
        assertEquals("FOREIGN_KEY_CONSTRAINT", failure.getMessage());
        assertNull(failure.getCause());
    }

    private static void insertUser(UUID id, String externalId) {
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :externalId, :externalId)")
                .param("id", id).param("externalId", externalId).update();
    }

    private static void insertDepartment(UUID id, UUID parentId, String name) {
        var statement = jdbc.sql("""
                INSERT INTO org_unit (id, external_id, parent_id, name)
                VALUES (:id, :externalId, :parentId, :name)
                """).param("id", id).param("externalId", id.toString()).param("name", name);
        if (parentId == null) {
            statement.param("parentId", null, java.sql.Types.OTHER).update();
        } else {
            statement.param("parentId", parentId).update();
        }
    }

    private static void insertDocument(UUID documentId, boolean archived, String status, boolean publish) {
        UUID versionId = UUID.nameUUIDFromBytes(documentId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO document (id, space_id, title, archived, created_at, updated_at)
                VALUES (:id, :space, :title, :archived, :at, :at)
                """).param("id", documentId).param("space", SPACE_ID).param("title", status)
                .param("archived", archived).param("at", timestamp(AT)).update();
        jdbc.sql("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_blob_id, status, parse_status,
                     created_by, created_at, status_changed_by, status_changed_at)
                VALUES (:id, :document, 1, :blob, :status, 'SUCCEEDED', :actor, :at, :actor, :at)
                """).param("id", versionId).param("document", documentId)
                .param("blob", "sha256/aa/" + documentId.toString().replace("-", "").repeat(2))
                .param("status", status).param("actor", ACTOR_ID).param("at", timestamp(AT)).update();
        if (publish) {
            jdbc.sql("UPDATE document SET current_published_version_id = :version WHERE id = :id")
                    .param("version", versionId).param("id", documentId).update();
        }
    }

    private static long aclCount(String scope, UUID documentId) {
        if (documentId == null) {
            return jdbc.sql("SELECT count(*) FROM acl_entry WHERE scope = :scope")
                    .param("scope", scope).query(Long.class).single();
        }
        return jdbc.sql("SELECT count(*) FROM acl_entry WHERE scope = :scope AND document_id = :document")
                .param("scope", scope).param("document", documentId).query(Long.class).single();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @TestConfiguration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfig {
        private static DataSource runtimeDataSource;

        @Bean
        DataSource dataSource() {
            return runtimeDataSource;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        JdbcPrincipalMembershipRepository memberships(JdbcClient jdbcClient) {
            return new JdbcPrincipalMembershipRepository(jdbcClient);
        }

        @Bean
        JdbcAccessDecisionRepository decisions(JdbcClient jdbcClient) {
            return new JdbcAccessDecisionRepository(jdbcClient);
        }

        @Bean
        JdbcAclCommandRepository commands(JdbcClient jdbcClient) {
            return new JdbcAclCommandRepository(jdbcClient);
        }

        @Bean
        JdbcAuditEventStore auditStore(JdbcClient jdbcClient) {
            return new JdbcAuditEventStore(jdbcClient);
        }

        @Bean
        AuditSink fixedAudit(JdbcAuditEventStore auditStore) {
            return command -> {
                var event = new AuditEvent(FIXED_AUDIT_ID, command.actorId(), command.action(),
                        command.resourceType(), command.resourceId(), AuditOutcome.SUCCEEDED,
                        command.metadata(), AT);
                auditStore.append(event);
                return event;
            };
        }

        @Bean
        AclService aclService(AclCommandRepository commands, AuditSink fixedAudit, JdbcClient jdbcClient) {
            return new AclService(commands, KnowledgeWriteAuthorization.allowAll(),
                    (spaceId, documentId) -> jdbcClient.sql("""
                                    SELECT count(*) FROM document WHERE space_id = :space AND id = :document
                                    """).param("space", spaceId).param("document", documentId)
                            .query(Long.class).single() == 1L,
                    fixedAudit);
        }
    }
}
