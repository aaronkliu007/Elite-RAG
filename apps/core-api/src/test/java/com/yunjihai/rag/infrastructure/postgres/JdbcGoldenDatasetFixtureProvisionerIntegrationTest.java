package com.yunjihai.rag.infrastructure.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.qa.evaluation.EvaluationDataset;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcGoldenDatasetFixtureProvisionerIntegrationTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-7000-8000-000000000100");
    private static final UUID ALLOWED_SPACE = UUID.fromString("10000000-0000-7000-8000-000000000100");
    private static final UUID RESTRICTED_SPACE = UUID.fromString("10000000-0000-7000-8000-000000000101");
    private static final Instant NOW = Instant.parse("2026-10-28T01:00:00Z");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_evaluation_fixture_test")
            .withUsername("migration_owner")
            .withPassword("migration_test_only");
    private static JdbcClient owner;
    private static JdbcClient runtime;
    private static TransactionTemplate transactions;
    private static EvaluationDataset dataset;

    @BeforeAll
    static void start() throws Exception {
        POSTGRES.start();
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_evaluation_fixture_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        owner = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        var runtimeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only");
        runtime = JdbcClient.create(runtimeDataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(runtimeDataSource));
        dataset = loadDataset();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clean() {
        owner.sql("DELETE FROM retrieval_projection_event").update();
        owner.sql("DELETE FROM acl_entry").update();
        owner.sql("DELETE FROM canonical_node").update();
        owner.sql("DELETE FROM canonical_document").update();
        owner.sql("UPDATE document SET current_published_version_id=NULL").update();
        owner.sql("DELETE FROM document_version").update();
        owner.sql("DELETE FROM document").update();
        owner.sql("DELETE FROM knowledge_space").update();
        owner.sql("DELETE FROM app_user").update();
    }

    @Test
    void provisionsRunnableAuthorityAndProjectionFixturesExactlyOnce() {
        var provisioner = provisioner();

        var first = provisioner.provision();
        var second = provisioner.provision();

        assertThat(first.installed()).isTrue();
        assertThat(first.accessibleDocuments()).isEqualTo(50);
        assertThat(first.restrictedDocuments()).isEqualTo(4);
        assertThat(second.installed()).isFalse();
        assertThat(new JdbcAuthorizedSpaceScope(runtime).resolve(ACTOR, NOW))
                .containsExactly(ALLOWED_SPACE)
                .doesNotContain(RESTRICTED_SPACE);
        assertThat(count("app_user")).isEqualTo(1);
        assertThat(count("knowledge_space")).isEqualTo(2);
        assertThat(count("document")).isEqualTo(54);
        assertThat(count("document_version")).isEqualTo(54);
        assertThat(count("canonical_node")).isEqualTo(54);
        assertThat(count("retrieval_projection_event")).isEqualTo(2);

        var expected = dataset.cases().stream()
                .flatMap(item -> item.expectedEvidence().stream())
                .findFirst().orElseThrow();
        var allowedSnapshot = new JdbcRetrievalProjectionSource(runtime)
                .load(ALLOWED_SPACE, expected.documentId(), 1);
        assertThat(allowedSnapshot.indexable()).isTrue();
        assertThat(allowedSnapshot.nodes()).extracting(node -> node.nodeId())
                .contains(expected.nodeId());
        assertThat(allowedSnapshot.spaceAclGrants())
                .anySatisfy(grant -> assertThat(grant.principalKey()).isEqualTo("USER:" + ACTOR));

        UUID forbidden = dataset.cases().stream()
                .flatMap(item -> item.forbiddenDocumentIds().stream())
                .findFirst().orElseThrow();
        var restrictedSnapshot = new JdbcRetrievalProjectionSource(runtime)
                .load(RESTRICTED_SPACE, forbidden, 2);
        assertThat(restrictedSnapshot.indexable()).isTrue();
        assertThat(restrictedSnapshot.spaceAclGrants()).isEmpty();
    }

    @Test
    void failsClosedWithoutPartialWritesWhenAReservedIdentityIsOccupied() {
        owner.sql("""
                INSERT INTO app_user(id,external_id,display_name,enabled)
                VALUES (:id,'unrelated-account','Unrelated Account',true)
                """).param("id", ACTOR).update();

        assertThatThrownBy(() -> provisioner().provision())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("EVALUATION_FIXTURE_ID_CONFLICT");

        assertThat(count("app_user")).isEqualTo(1);
        assertThat(count("knowledge_space")).isZero();
        assertThat(count("document")).isZero();
        assertThat(count("retrieval_projection_event")).isZero();
    }

    @Test
    void failsClosedWhenThePermissionTrapSpaceHasBeenAuthorized() {
        provisioner().provision();
        owner.sql("""
                INSERT INTO acl_entry(
                    id,space_id,document_id,scope,principal_type,principal_id,
                    expires_at,revoked_at,created_at,updated_at)
                VALUES (:id,:spaceId,NULL,'SPACE','USER',:actorId,NULL,NULL,:at,:at)
                """)
                .param("id", UUID.randomUUID())
                .param("spaceId", RESTRICTED_SPACE)
                .param("actorId", ACTOR)
                .param("at", java.time.OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .update();

        assertThatThrownBy(() -> provisioner().provision())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("EVALUATION_FIXTURE_ID_CONFLICT");
    }

    private JdbcGoldenDatasetFixtureProvisioner provisioner() {
        return new JdbcGoldenDatasetFixtureProvisioner(
                runtime,
                transactions,
                new JdbcProjectionEventRepository(runtime),
                dataset,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private long count(String table) {
        return owner.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static EvaluationDataset loadDataset() throws IOException {
        try (var input = JdbcGoldenDatasetFixtureProvisionerIntegrationTest.class.getResourceAsStream(
                "/contracts/qa/golden-dataset-v1.json")) {
            if (input == null) throw new IllegalStateException("golden dataset is missing");
            return new ObjectMapper().findAndRegisterModules().readValue(input, EvaluationDataset.class);
        }
    }
}
