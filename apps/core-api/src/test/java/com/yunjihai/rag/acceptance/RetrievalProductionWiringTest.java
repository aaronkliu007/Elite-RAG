package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.RagApplication;
import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.access.PrincipalType;
import com.yunjihai.rag.access.role.RbacKnowledgeAuthorization;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchClientFactory;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchConnection;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchIndexManager;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchProperties;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchRebuildService;
import com.yunjihai.rag.retrieval.TrustedRetrievalService;
import com.yunjihai.rag.retrieval.PrincipalResolverPort;
import com.yunjihai.rag.retrieval.domain.DegradationReason;
import com.yunjihai.rag.retrieval.domain.RetrievalFilter;
import com.yunjihai.rag.retrieval.projection.ProjectionWorker;
import com.yunjihai.rag.retrieval.search.HybridRecallService;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalProductionWiringTest {
    private static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            DockerImageName.parse("opensearchproject/opensearch:3.8.0"))
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));
    private static URI endpoint;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        LocalFoundationInfrastructure.start();
        OPENSEARCH.start();
        endpoint = URI.create("http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
        try (OpenSearchConnection connection = new OpenSearchClientFactory().create(
                new OpenSearchProperties(endpoint, null, null))) {
            var indexes = new OpenSearchIndexManager(connection.client());
            indexes.createGeneration("chunks-wiring", OptionalInt.empty());
            indexes.switchAliases(null, "chunks-wiring");
        }
    }

    @AfterAll
    static void stopInfrastructure() {
        OPENSEARCH.stop();
        LocalFoundationInfrastructure.stop();
    }

    @Test
    void localProfileWiresProductionAdaptersAndTreatsDisabledEmbeddingAsDegradation() throws Exception {
        try (var context = startContext()) {
            assertThat(context.getBean(ProjectionWorker.class)).isNotNull();
            assertThat(context.getBean(OpenSearchRebuildService.class)).isNotNull();
            assertThat(context.getBean(TrustedRetrievalService.class)).isNotNull();
            assertThat(context.getBean(KnowledgeWriteAuthorization.class))
                    .isInstanceOf(RbacKnowledgeAuthorization.class);

            var health = context.getBean(HealthContributorRegistry.class);
            assertHealth(health, "opensearchRead", Status.UP);
            assertHealth(health, "opensearchProjection", Status.UP);
            var embedding = assertHealth(health, "embeddingCapability", Status.UP);
            assertThat(embedding.getDetails()).containsEntry("capability", "DISABLED");
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            var readiness = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + port + "/actuator/health/readiness"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(readiness.statusCode()).as(readiness.body()).isEqualTo(200);
            assertThat(readiness.body()).contains("\"status\":\"UP\"");

            UUID userId = UUID.randomUUID();
            var result = context.getBean(HybridRecallService.class).recall(
                    "部署方案",
                    new RetrievalFilter(
                            UUID.randomUUID(),
                            Set.of(new PrincipalRef(PrincipalType.USER, userId)),
                            true,
                            Instant.parse("2026-09-30T10:00:00Z"),
                            Map.of()));
            assertThat(result.candidates()).isEmpty();
            assertThat(result.degradationReasons()).containsExactly(DegradationReason.VECTOR_UNAVAILABLE);

            var jdbc = context.getBean(JdbcClient.class);
            UUID disabledUser = UUID.randomUUID();
            jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :external, 'Disabled')")
                    .param("id", disabledUser).param("external", disabledUser.toString()).update();
            var resolver = context.getBean(PrincipalResolverPort.class);
            assertThat(resolver.resolve(disabledUser, Instant.now()))
                    .contains(new PrincipalRef(PrincipalType.USER, disabledUser));
            jdbc.sql("UPDATE app_user SET enabled = false WHERE id = :id")
                    .param("id", disabledUser).update();
            assertThat(resolver.resolve(disabledUser, Instant.now())).isEmpty();
        }
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
                Map.entry("rag.bootstrap-admin.username", "wiring-admin"),
                Map.entry("rag.bootstrap-admin.password", "wiring-admin-password"),
                Map.entry("rag.bootstrap-admin.display-name", "Wiring Admin"),
                Map.entry("spring.autoconfigure.exclude", String.join(",",
                        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
                        "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
                        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")),
                Map.entry("logging.level.root", "ERROR"));
        return new SpringApplicationBuilder(RagApplication.class)
                .profiles("local")
                .initializers(application -> application.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("retrieval-wiring-test", properties)))
                .run();
    }

    private static org.springframework.boot.health.contributor.Health assertHealth(
            HealthContributorRegistry registry, String name, Status expected) {
        var contributor = registry.getContributor(name);
        assertThat(contributor).as("health contributor %s", name).isInstanceOf(HealthIndicator.class);
        var health = ((HealthIndicator) contributor).health();
        assertThat(health.getStatus()).isEqualTo(expected);
        return health;
    }
}
