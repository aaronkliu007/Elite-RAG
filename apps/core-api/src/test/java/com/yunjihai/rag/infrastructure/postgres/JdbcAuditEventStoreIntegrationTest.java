package com.yunjihai.rag.infrastructure.postgres;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditEvent;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.audit.AuditService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JdbcAuditEventStoreIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_audit_test").withUsername("migration_owner").withPassword("migration_test_only");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static JdbcClient runtime;
    private static JdbcAuditEventStore store;

    @BeforeAll
    static void setUp() throws SQLException {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_audit_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        runtime = JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only"));
        store = new JdbcAuditEventStore(runtime);
    }

    @AfterAll
    static void tearDown() {
        POSTGRES.stop();
    }

    @Test
    void appendPersistsOnlyWhitelistedMetadataWithoutUpdateOrDeletePath() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        var service = new AuditService(store, Clock.fixed(Instant.parse("2026-09-29T08:07:06.123456Z"), ZoneOffset.UTC));
        AuditEvent event = service.record(new AuditCommand(actor, AuditAction.VIEW, "DOCUMENT", resource,
                AuditOutcome.SUCCEEDED, Map.of("requestId", "request-42", "versionId", resource.toString())));

        var row = runtime.sql("SELECT actor_id, action, resource_type, resource_id, result, metadata::text AS metadata FROM audit_event WHERE id = :id")
                .param("id", event.id()).query((rs, n) -> Map.of(
                        "actor", rs.getObject("actor_id", UUID.class),
                        "action", rs.getString("action"),
                        "resourceType", rs.getString("resource_type"),
                        "resource", rs.getObject("resource_id", UUID.class),
                        "result", rs.getString("result"),
                        "metadata", rs.getString("metadata"))).single();
        assertEquals(actor, row.get("actor"));
        assertEquals("VIEW", row.get("action"));
        assertEquals("DOCUMENT", row.get("resourceType"));
        assertEquals(resource, row.get("resource"));
        assertEquals("SUCCEEDED", row.get("result"));
        assertEquals(Map.of("requestId", "request-42", "versionId", resource.toString()),
                JSON.readValue((String) row.get("metadata"), new TypeReference<Map<String, String>>() {}));

        assertThrows(IllegalArgumentException.class, () -> service.record(new AuditCommand(actor,
                AuditAction.EDIT, "DOCUMENT", resource, AuditOutcome.SUCCEEDED, Map.of("body", "secret"))));
        assertEquals(1L, runtime.sql("SELECT count(*) FROM audit_event WHERE resource_id = :resource")
                .param("resource", resource).query(Long.class).single());
        assertThrows(DataAccessException.class, () -> runtime.sql("UPDATE audit_event SET action = 'EDIT' WHERE id = :id")
                .param("id", event.id()).update());
        assertThrows(DataAccessException.class, () -> runtime.sql("DELETE FROM audit_event WHERE id = :id")
                .param("id", event.id()).update());
    }

    @Test
    void duplicateAuditIdIsRejectedWithStableCode() {
        AuditEvent event = event(UUID.randomUUID());
        store.append(event);
        var failure = assertThrows(PostgresPersistenceExceptionTranslator.Failure.class, () -> store.append(event));
        assertSafeFailure(failure, "UNIQUE_CONSTRAINT");
    }

    @Test
    void translatesForeignKeyCheckOptimisticLockAndUnknownFailuresToSafeCodes() {
        assertSqlFailureCode("INSERT INTO org_membership (user_id, org_unit_id, snapshot_at) VALUES (:id, :id, now())",
                "FOREIGN_KEY_CONSTRAINT");
        assertSqlFailureCode("INSERT INTO app_user (id, external_id, display_name, session_version) VALUES (:id, 'bad', 'Bad', -1)",
                "CHECK_CONSTRAINT");
        assertSafeFailure(PostgresPersistenceExceptionTranslator.translate(
                new OptimisticLockingFailureException("secret SQL and connection string")), "OPTIMISTIC_LOCK");
        assertSafeFailure(PostgresPersistenceExceptionTranslator.translate(
                new DataAccessResourceFailureException("secret SQL and connection string")), "DATABASE_FAILURE");
        assertSafeFailure(PostgresPersistenceExceptionTranslator.translate(
                new DataAccessResourceFailureException("secret SQL and connection string",
                        new SQLException("driver detail and constraint name"))), "DATABASE_FAILURE");
    }

    private static void assertSqlFailureCode(String sql, String expectedCode) {
        var cause = assertThrows(DataAccessException.class,
                () -> runtime.sql(sql).param("id", UUID.randomUUID()).update());
        assertSafeFailure(PostgresPersistenceExceptionTranslator.translate(cause), expectedCode);
    }

    private static void assertSafeFailure(PostgresPersistenceExceptionTranslator.Failure failure, String code) {
        assertEquals(code, failure.code());
        assertEquals(code, failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    private static AuditEvent event(UUID id) {
        return new AuditEvent(id, UUID.randomUUID(), AuditAction.VIEW, "DOCUMENT", UUID.randomUUID(),
                AuditOutcome.SUCCEEDED, Map.of("requestId", "request-42"), Instant.parse("2026-09-29T08:07:06.123456Z"));
    }
}
