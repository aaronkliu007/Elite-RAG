package com.yunjihai.rag.infrastructure.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class GroundedQaMigrationIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_qa_migration_test").withUsername("migration_owner")
            .withPassword("migration_test_only");
    private static JdbcClient jdbc;

    @BeforeAll
    static void migrate() throws Exception {
        POSTGRES.start();
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_qa_migration_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test
    void createsOnlyApprovedQaTablesWithoutRawQuestionOrEvidenceBodyColumns() {
        var tables = Set.copyOf(jdbc.sql("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name LIKE 'qa_%'
                """).query(String.class).list());
        assertThat(tables).containsExactlyInAnyOrder(
                "qa_request", "qa_evidence_snapshot", "qa_answer", "qa_model_call_audit");
        var columns = jdbc.sql("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name LIKE 'qa_%'
                """).query(String.class).list();
        assertThat(columns).contains("qa_request.question_digest", "qa_answer.answer_text")
                .noneMatch(name -> name.contains("question_text") || name.contains("excerpt")
                        || name.contains("prompt") || name.contains("raw_response"));
    }

    @Test
    void bindsSnapshotsToCanonicalNodesAndAppliesMinimumRuntimePrivileges() {
        String evidenceConstraints = jdbc.sql("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'qa_evidence_snapshot'::regclass
                """).query(String.class).list().toString();
        assertThat(evidenceConstraints)
                .contains("FOREIGN KEY (version_id, node_id) REFERENCES canonical_node(version_id, node_id)");

        assertThat(privileges("qa_request")).containsExactlyInAnyOrder("INSERT","SELECT","UPDATE");
        assertThat(privileges("qa_evidence_snapshot")).containsExactlyInAnyOrder("INSERT","SELECT");
        assertThat(privileges("qa_answer")).containsExactlyInAnyOrder("INSERT","SELECT");
        assertThat(privileges("qa_model_call_audit")).containsExactlyInAnyOrder("INSERT","SELECT");
    }

    @Test
    void appliesStatusAndTerminalFieldConstraints() {
        String definition = jdbc.sql("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'qa_request'::regclass
                """).query(String.class).list().toString();
        assertThat(definition).contains("RECEIVED", "ANSWERED", "REFUSED", "FAILED",
                "question_digest", "revision", "refusal_reason", "failure_code");
    }

    private java.util.List<String> privileges(String table) {
        return jdbc.sql("""
                SELECT privilege_type FROM information_schema.role_table_grants
                WHERE grantee='rag_runtime_test' AND table_schema='public' AND table_name=:table
                """).param("table",table).query(String.class).list();
    }
}
