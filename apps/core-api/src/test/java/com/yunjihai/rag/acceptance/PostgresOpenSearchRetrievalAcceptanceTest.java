package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.RagApplication;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchClientFactory;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchConnection;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchIndexManager;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchProperties;
import com.yunjihai.rag.retrieval.TrustedRetrievalService;
import com.yunjihai.rag.retrieval.domain.DegradationReason;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.domain.RetrievalQuery;
import com.yunjihai.rag.retrieval.projection.ProjectionChangePublisher;
import com.yunjihai.rag.retrieval.projection.ProjectionWorker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresOpenSearchRetrievalAcceptanceTest {
    private static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            DockerImageName.parse("opensearchproject/opensearch:3.8.0"))
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));
    private static final String INDEX = "chunks-acceptance";
    private static URI endpoint;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        LocalFoundationInfrastructure.start();
        OPENSEARCH.start();
        endpoint = URI.create("http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
        try (OpenSearchConnection connection = new OpenSearchClientFactory().create(
                new OpenSearchProperties(endpoint, null, null))) {
            var indexes = new OpenSearchIndexManager(connection.client());
            indexes.createGeneration(INDEX, OptionalInt.empty());
            indexes.switchAliases(null, INDEX);
        }
    }

    @AfterAll
    static void stopInfrastructure() {
        OPENSEARCH.stop();
        LocalFoundationInfrastructure.stop();
    }

    @Test
    void staleOpenSearchGrantIsRemovedByFinalPostgresAuthorization() throws Exception {
        try (var context = startContext()) {
            var jdbc = context.getBean(JdbcClient.class);
            UUID user = UUID.randomUUID();
            UUID space = UUID.randomUUID();
            UUID document = UUID.randomUUID();
            UUID version = UUID.randomUUID();
            UUID acl = UUID.randomUUID();
            seedPublishedDocument(jdbc, user, space, document, version, acl);

            var transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            var publisher = context.getBean(ProjectionChangePublisher.class);
            transactions.executeWithoutResult(status -> publisher.withDocumentChangeLock(document, () -> {
                publisher.documentAclChanged(space, document, Instant.now());
                return null;
            }));

            assertThat(context.getBean(ProjectionWorker.class).runOnce(
                    "acceptance-worker", OpenSearchIndexManager.WRITE_ALIAS)).isEqualTo(1);

            var service = context.getBean(TrustedRetrievalService.class);
            var query = new RetrievalQuery(
                    UUID.randomUUID(), user, space, "Kubernetes 灰度部署方案", Map.of(), 8);
            var first = service.retrieve(query);

            assertThat(first.evidence()).hasSize(1);
            assertThat(first.evidence().getFirst().documentId()).isEqualTo(document);
            assertThat(first.evidence().getFirst().locator()).isInstanceOf(MarkdownLocator.class);
            assertThat(first.degradationReasons()).contains(
                    DegradationReason.VECTOR_UNAVAILABLE,
                    DegradationReason.RERANKER_UNAVAILABLE);

            jdbc.sql("UPDATE acl_entry SET revoked_at = :at WHERE id = :id")
                    .param("at", OffsetDateTime.now(ZoneOffset.UTC))
                    .param("id", acl)
                    .update();

            try (OpenSearchConnection connection = new OpenSearchClientFactory().create(
                    new OpenSearchProperties(endpoint, null, null))) {
                assertThat(new OpenSearchIndexManager(connection.client())
                        .countNodes(OpenSearchIndexManager.READ_ALIAS)).isEqualTo(1);
            }
            var afterRevocation = service.retrieve(new RetrievalQuery(
                    UUID.randomUUID(), user, space, "Kubernetes 灰度部署方案", Map.of(), 8));
            assertThat(afterRevocation.stats().fusedCount()).isEqualTo(1);
            assertThat(afterRevocation.stats().reauthorizedCount()).isZero();
            assertThat(afterRevocation.evidence()).isEmpty();
        }
    }

    private static void seedPublishedDocument(
            JdbcClient jdbc, UUID user, UUID space, UUID document, UUID version, UUID acl) {
        Instant publishedAt = Instant.now().minusSeconds(60);
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :external, '验收用户')")
                .param("id", user).param("external", "acceptance-" + user).update();
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by) VALUES (:id, '验收空间', :user)")
                .param("id", space).param("user", user).update();
        jdbc.sql("""
                INSERT INTO document (id, space_id, title, archived, revision)
                VALUES (:id, :space, '企业部署方案', false, 1)
                """).param("id", document).param("space", space).update();
        jdbc.sql("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_blob_id, status, parse_status,
                     created_by, status_changed_at)
                VALUES (:id, :document, 1, 'blob', 'PUBLISHED', 'SUCCEEDED', :user, :publishedAt)
                """).param("id", version).param("document", document).param("user", user)
                .param("publishedAt", OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC)).update();
        jdbc.sql("UPDATE document SET current_published_version_id = :version WHERE id = :document")
                .param("version", version).param("document", document).update();
        jdbc.sql("""
                INSERT INTO canonical_document
                    (version_id, source_filename, media_type, page_count, parsed_at)
                VALUES (:version, 'deployment.md', 'text/markdown', 1, :parsedAt)
                """).param("version", version)
                .param("parsedAt", OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC)).update();
        jdbc.sql("""
                INSERT INTO canonical_node
                    (version_id, node_id, kind, body_text, parent_node_id, page,
                     bounding_box, table_cells, confidence, stable_order)
                VALUES (:version, 'node-1', 'PARAGRAPH', 'Kubernetes 灰度部署方案与回滚步骤',
                        :parent, 1, CAST('null' AS jsonb), CAST('null' AS jsonb), 0.99, 1)
                """).param("version", version).param("parent", null, Types.VARCHAR).update();
        jdbc.sql("""
                INSERT INTO acl_entry
                    (id, space_id, document_id, scope, principal_type, principal_id)
                VALUES (:id, :space, :document, 'SPACE', 'USER', :user)
                """).param("id", acl).param("space", space)
                .param("document", null, Types.OTHER).param("user", user).update();
    }

    private static ConfigurableApplicationContext startContext() {
        Map<String, Object> properties = Map.ofEntries(
                Map.entry("server.port", "0"),
                Map.entry("spring.datasource.url", LocalFoundationInfrastructure.jdbcUrl()),
                Map.entry("spring.datasource.username", LocalFoundationInfrastructure.RUNTIME_USER),
                Map.entry("spring.datasource.password", LocalFoundationInfrastructure.RUNTIME_PASSWORD),
                Map.entry("spring.flyway.url", LocalFoundationInfrastructure.jdbcUrl()),
                Map.entry("spring.flyway.user", LocalFoundationInfrastructure.MIGRATION_USER),
                Map.entry("spring.flyway.password", LocalFoundationInfrastructure.MIGRATION_PASSWORD),
                Map.entry("rag.minio.endpoint", LocalFoundationInfrastructure.minioEndpoint()),
                Map.entry("rag.minio.access-key", LocalFoundationInfrastructure.MINIO_APP_USER),
                Map.entry("rag.minio.secret-key", LocalFoundationInfrastructure.MINIO_APP_PASSWORD),
                Map.entry("rag.minio.bucket", LocalFoundationInfrastructure.BUCKET),
                Map.entry("rag.opensearch.enabled", "true"),
                Map.entry("rag.opensearch.endpoint", endpoint.toString()),
                Map.entry("rag.retrieval.vector.enabled", "false"),
                Map.entry("rag.bootstrap-admin.username", "retrieval-admin"),
                Map.entry("rag.bootstrap-admin.password", "retrieval-admin-password"),
                Map.entry("rag.bootstrap-admin.display-name", "Retrieval Admin"),
                Map.entry("spring.autoconfigure.exclude", String.join(",",
                        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
                        "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
                        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")),
                Map.entry("logging.level.root", "ERROR"));
        return new SpringApplicationBuilder(RagApplication.class)
                .profiles("local")
                .initializers(application -> application.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("retrieval-acceptance-test", properties)))
                .run();
    }
}
