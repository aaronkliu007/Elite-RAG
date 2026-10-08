package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.knowledge.KnowledgeSpace;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcKnowledgeSpaceRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID DISABLED = UUID.fromString("00000000-0000-7000-8000-000000000003");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_space_test")
            .withUsername("migration_owner")
            .withPassword("migration_test_only");

    private static JdbcClient jdbc;
    private static JdbcKnowledgeSpaceRepository repository;

    @BeforeAll
    static void startDatabase() throws Exception {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_space_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only"));
        repository = new JdbcKnowledgeSpaceRepository(jdbc);
    }

    @AfterEach
    void clear() {
        jdbc.sql("DELETE FROM acl_entry").update();
        jdbc.sql("DELETE FROM space_role_assignment").update();
        jdbc.sql("DELETE FROM system_role_assignment").update();
        jdbc.sql("DELETE FROM knowledge_space").update();
        jdbc.sql("DELETE FROM app_user").update();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void persistsDescriptionAndUsesRevisionForOptimisticReplacement() {
        insertUser(ADMIN, "admin", true);
        var original = space(100, "研发制度", "初始说明", 0);
        repository.insert(original);

        assertThat(repository.findById(original.id())).contains(original);
        var updated = new KnowledgeSpace(
                original.id(), original.name(), "更新说明", 1, original.createdBy(), original.createdAt());
        assertThat(repository.replace(0, updated)).isTrue();
        assertThat(repository.replace(0, updated)).isFalse();
        assertThat(repository.findById(original.id())).contains(updated);
    }

    @Test
    void listAuthorizedFiltersInSqlAndPaginatesFromZeroBasedOffset() {
        insertUser(ADMIN, "admin", true);
        insertUser(MEMBER, "member", true);
        insertUser(DISABLED, "disabled", false);
        var roleSpace = space(101, "角色空间", "", 0);
        var aclSpace = space(102, "ACL 空间", "", 0);
        var hiddenSpace = space(103, "隐藏空间", "", 0);
        repository.insert(roleSpace);
        repository.insert(aclSpace);
        repository.insert(hiddenSpace);
        jdbc.sql("""
                INSERT INTO system_role_assignment (id, user_id, role, granted_by, granted_at)
                VALUES (:id, :user, 'SYSTEM_ADMIN', :user, :at)
                """).param("id", id(201)).param("user", ADMIN).param("at", timestamp(NOW)).update();
        grantRole(roleSpace.id(), MEMBER, "READER", 202);
        grantRole(hiddenSpace.id(), DISABLED, "SPACE_ADMIN", 203);
        jdbc.sql("""
                INSERT INTO acl_entry
                    (id, space_id, scope, principal_type, principal_id, created_at, updated_at)
                VALUES (:id, :space, 'SPACE', 'USER', :user, :at, :at)
                """).param("id", id(204)).param("space", aclSpace.id()).param("user", MEMBER)
                .param("at", timestamp(NOW)).update();

        assertThat(repository.listAuthorized(ADMIN, 0, 10)).hasSize(3);
        assertThat(repository.listAuthorized(MEMBER, 0, 1))
                .extracting(KnowledgeSpace::name).containsExactly("ACL 空间");
        assertThat(repository.listAuthorized(MEMBER, 1, 10))
                .extracting(KnowledgeSpace::name).containsExactly("角色空间");
        assertThat(repository.listAuthorized(DISABLED, 0, 10)).isEmpty();
    }

    private static void grantRole(UUID spaceId, UUID userId, String role, int suffix) {
        jdbc.sql("""
                INSERT INTO space_role_assignment (id, space_id, user_id, role, granted_by, granted_at)
                VALUES (:id, :space, :user, :role, :admin, :at)
                """).param("id", id(suffix)).param("space", spaceId).param("user", userId)
                .param("role", role).param("admin", ADMIN).param("at", timestamp(NOW)).update();
    }

    private static KnowledgeSpace space(int suffix, String name, String description, long revision) {
        return new KnowledgeSpace(id(suffix), name, description, revision, ADMIN, NOW.plusSeconds(suffix));
    }

    private static void insertUser(UUID id, String externalId, boolean enabled) {
        jdbc.sql("""
                INSERT INTO app_user (id, external_id, display_name, enabled)
                VALUES (:id, :external, :external, :enabled)
                """).param("id", id).param("external", externalId).param("enabled", enabled).update();
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-7000-8000-%012d".formatted(suffix));
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
