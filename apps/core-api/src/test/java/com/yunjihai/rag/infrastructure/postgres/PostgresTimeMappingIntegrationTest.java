package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditEvent;
import com.yunjihai.rag.audit.AuditOutcome;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresTimeMappingIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_time_test").withUsername("migration_owner").withPassword("migration_test_only");
    private static JdbcClient runtime;
    private static JdbcAuditEventStore store;

    @BeforeAll
    static void setUp() throws SQLException {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_time_test SET rag.runtime_role = 'rag_runtime_test'");
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
    void instantRoundTripsIndependentOfJvmDefaultTimezoneWithinMicrosecondPrecision() {
        TimeZone original = TimeZone.getDefault();
        Instant expected = Instant.parse("2026-09-29T08:07:06.123456789Z");
        try {
            for (String timezone : new String[] { "UTC", "Asia/Shanghai" }) {
                TimeZone.setDefault(TimeZone.getTimeZone(timezone));
                UUID id = UUID.randomUUID();
                store.append(new AuditEvent(id, UUID.randomUUID(), AuditAction.VIEW, "DOCUMENT", UUID.randomUUID(),
                        AuditOutcome.SUCCEEDED, Map.of(), expected));
                Instant actual = runtime.sql("SELECT occurred_at FROM audit_event WHERE id = :id")
                        .param("id", id).query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant()).single();
                assertTrue(Duration.between(expected, actual).abs().compareTo(Duration.ofNanos(1_000)) < 0,
                        timezone + " must round-trip within PostgreSQL microsecond precision");
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
