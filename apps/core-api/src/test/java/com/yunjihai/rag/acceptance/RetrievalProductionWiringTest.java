package com.yunjihai.rag.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yunjihai.rag.RagApplication;
import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.access.PrincipalType;
import com.yunjihai.rag.access.role.RbacKnowledgeAuthorization;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchClientFactory;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchConnection;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchIndexManager;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchProperties;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchRebuildService;
import com.yunjihai.rag.infrastructure.model.OpenAiEmbeddingAdapter;
import com.yunjihai.rag.infrastructure.model.OpenAiRerankerAdapter;
import com.yunjihai.rag.retrieval.TrustedRetrievalService;
import com.yunjihai.rag.retrieval.PrincipalResolverPort;
import com.yunjihai.rag.retrieval.domain.Candidate;
import com.yunjihai.rag.retrieval.domain.DegradationReason;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.domain.RecallSource;
import com.yunjihai.rag.retrieval.domain.RetrievalFilter;
import com.yunjihai.rag.retrieval.domain.ScoredCandidate;
import com.yunjihai.rag.retrieval.embedding.EmbeddingPort;
import com.yunjihai.rag.retrieval.projection.ProjectionAclGrant;
import com.yunjihai.rag.retrieval.projection.ProjectionNode;
import com.yunjihai.rag.retrieval.projection.ProjectionWorker;
import com.yunjihai.rag.retrieval.projection.RetrievalProjectionSnapshot;
import com.yunjihai.rag.retrieval.projection.SearchProjectionWriter;
import com.yunjihai.rag.runtime.IngestionWorkerScheduler;
import com.yunjihai.rag.runtime.ProjectionWorkerScheduler;
import com.yunjihai.rag.ingestion.UploadService;
import com.yunjihai.rag.retrieval.search.HybridRecallService;
import com.yunjihai.rag.retrieval.search.VectorRetriever;
import com.yunjihai.rag.retrieval.selection.RerankerPort;
import com.yunjihai.rag.retrieval.selection.RerankingService;
import com.yunjihai.rag.knowledge.DocumentService;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import com.yunjihai.rag.knowledge.AuthorizedDocumentQuery;
import com.yunjihai.rag.knowledge.web.DocumentController;
import com.yunjihai.rag.knowledge.web.UploadController;
import com.yunjihai.rag.qa.GroundedQaService;
import com.yunjihai.rag.qa.web.QaController;
import com.yunjihai.rag.qa.web.QaSourceController;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalProductionWiringTest {
    private static final ObjectMapper JSON = new ObjectMapper();
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
    private static HttpServer modelGateway;
    private static URI modelEndpoint;
    private static final List<String> MODEL_REQUEST_BODIES =
            java.util.Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void startInfrastructure() throws Exception {
        LocalFoundationInfrastructure.start();
        OPENSEARCH.start();
        modelGateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        modelGateway.createContext("/v1/embeddings", RetrievalProductionWiringTest::embeddingResponse);
        modelGateway.start();
        modelEndpoint = URI.create("http://127.0.0.1:" + modelGateway.getAddress().getPort());
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
        if (modelGateway != null) modelGateway.stop(0);
        OPENSEARCH.stop();
        LocalFoundationInfrastructure.stop();
    }

    @Test
    void localProfileWiresProductionAdaptersAndTreatsDisabledEmbeddingAsDegradation() throws Exception {
        try (var context = startContext()) {
            assertThat(context.getBean(ProjectionWorker.class)).isNotNull();
            assertThat(context.getBean(OpenSearchRebuildService.class)).isNotNull();
            assertThat(context.getBean(TrustedRetrievalService.class)).isNotNull();
            assertThat(context.getBean(com.yunjihai.rag.solution.application.DocumentRequirementProposalService.class)).isNotNull();
            assertThat(context.getBean(com.yunjihai.rag.solution.web.DocumentRequirementProposalController.class)).isNotNull();
            var mappings = context.getBean("requestMappingHandlerMapping", org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class)
                    .getHandlerMethods().keySet().stream().map(Object::toString).toList();
            assertThat(mappings).anyMatch(value -> value.contains("/document-requirement-proposals"));
            assertThat(context.getBean(IngestionWorkerScheduler.class)).isNotNull();
            assertThat(context.getBean(ProjectionWorkerScheduler.class)).isNotNull();
            assertThat(context.getBean(DocumentController.class)).isNotNull();
            assertThat(context.getBean("runtimeUploadService", UploadService.class)).isNotNull();
            assertThat(context.getBean(UploadController.class)).isNotNull();
            assertThat(context.getBean(GroundedQaService.class)).isNotNull();
            assertThat(context.getBean(QaController.class)).isNotNull();
            assertThat(context.getBean(QaSourceController.class)).isNotNull();
            var multipart = context.getBean(MultipartProperties.class);
            assertThat(multipart.getMaxFileSize().toBytes()).isEqualTo(100L * 1024 * 1024);
            assertThat(multipart.getMaxRequestSize().toBytes()).isGreaterThan(100L * 1024 * 1024);
            assertThat(context.getBean(KnowledgeWriteAuthorization.class))
                    .isInstanceOf(RbacKnowledgeAuthorization.class);
            assertThat(context.getBean(AuthorizedDocumentQuery.class).listAuthorized(
                    UUID.randomUUID(), UUID.randomUUID(), Instant.now(), 0, 20)).isEmpty();

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
                            Set.of(UUID.randomUUID()),
                            Set.of(new PrincipalRef(PrincipalType.USER, userId)),
                            true,
                            Instant.parse("2026-09-30T10:00:00Z"),
                            Map.of()));
            assertThat(result.candidates()).isEmpty();
            assertThat(result.degradationReasons()).containsExactly(DegradationReason.VECTOR_UNAVAILABLE);

            var reranked = context.getBean(RerankingService.class)
                    .rerank("部署方案", List.of(candidate()));
            assertThat(reranked.candidates()).hasSize(1);
            assertThat(reranked.degradationReasons())
                    .containsExactly(DegradationReason.RERANKER_UNAVAILABLE);

            var jdbc = context.getBean(JdbcClient.class);
            UUID bootstrapAdmin = jdbc.sql("""
                            SELECT user_id
                            FROM local_credential
                            WHERE username_normalized = 'wiring-admin'
                            """)
                    .query(UUID.class)
                    .single();
            UUID writableSpace = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO knowledge_space (id, name, created_by)
                            VALUES (:id, 'Document creation transaction check', :createdBy)
                            """)
                    .param("id", writableSpace)
                    .param("createdBy", bootstrapAdmin)
                    .update();
            context.getBean(DocumentService.class).requireCreateDocument(bootstrapAdmin, writableSpace);

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

    @Test
    void chatOnlyConfigurationWiresWithoutEmbeddingOrRerankerModels() {
        try (var context = startContext(Map.ofEntries(
                Map.entry("rag.workers.enabled", "false"),
                Map.entry("rag.ai.enabled", "true"),
                Map.entry("rag.ai.provider", "deepseek"),
                Map.entry("rag.ai.config-version", "chat-only-test"),
                Map.entry("rag.ai.endpoint", modelEndpoint.toString()),
                Map.entry("rag.ai.approved-hosts", "127.0.0.1"),
                Map.entry("rag.ai.api-key", "test-key"),
                Map.entry("rag.ai.embedding-model", ""),
                Map.entry("rag.ai.reranker-model", ""),
                Map.entry("rag.ai.chat-model", "deepseek-flash"),
                Map.entry("rag.ai.allow-insecure-loopback-for-tests", "true")))) {
            assertThat(context.getBean(com.yunjihai.rag.qa.model.ChatCompletionPort.class))
                    .isInstanceOf(com.yunjihai.rag.infrastructure.model.OpenAiChatCompletionAdapter.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    context.getBean(EmbeddingPort.class).embedAll(List.of("测试")))
                    .isInstanceOf(com.yunjihai.rag.retrieval.embedding.EmbeddingUnavailableException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    context.getBean(RerankerPort.class).rerank("测试", List.of()))
                    .isInstanceOf(com.yunjihai.rag.qa.model.ModelGatewayException.class);
        }
    }

    @Test
    void vectorEnabledWiresApprovedAdaptersAndUsesOneDimensionForRebuildProjectionAndQuery()
            throws Exception {
        MODEL_REQUEST_BODIES.clear();
        try (var context = startContext(Map.ofEntries(
                Map.entry("rag.workers.enabled", "false"),
                Map.entry("rag.ai.enabled", "true"),
                Map.entry("rag.ai.provider", "approved-openai-compatible"),
                Map.entry("rag.ai.config-version", "wiring-config-v1"),
                Map.entry("rag.ai.endpoint", modelEndpoint.toString()),
                Map.entry("rag.ai.approved-hosts", "127.0.0.1"),
                Map.entry("rag.ai.api-key", "sk-wiring-test"),
                Map.entry("rag.ai.embedding-model", "embedding-wiring"),
                Map.entry("rag.ai.reranker-model", "reranker-wiring"),
                Map.entry("rag.ai.chat-model", "chat-wiring"),
                Map.entry("rag.ai.connect-timeout", "PT1S"),
                Map.entry("rag.ai.response-timeout", "PT2S"),
                Map.entry("rag.ai.total-timeout", "PT3S"),
                Map.entry("rag.ai.max-input-tokens", "8000"),
                Map.entry("rag.ai.max-output-tokens", "2000"),
                Map.entry("rag.ai.max-concurrency", "2"),
                Map.entry("rag.ai.daily-deployment-token-limit", "100000"),
                Map.entry("rag.ai.allow-insecure-loopback-for-tests", "true"),
                Map.entry("rag.retrieval.vector.enabled", "true"),
                Map.entry("rag.retrieval.vector.dimension", "3")))) {
            assertThat(context.getBean(EmbeddingPort.class)).isInstanceOf(OpenAiEmbeddingAdapter.class);
            assertThat(context.getBean(RerankerPort.class)).isInstanceOf(OpenAiRerankerAdapter.class);
            var health = context.getBean(HealthContributorRegistry.class);
            var embedding = assertHealth(health, "embeddingCapability", Status.UP);
            assertThat(embedding.getDetails())
                    .containsEntry("capability", "ENABLED")
                    .containsEntry("dimension", 3);

            String generation = "chunks-wiring-vector";
            context.getBean(OpenSearchRebuildService.class)
                    .rebuild(generation, Duration.ofSeconds(10));
            UUID spaceId = UUID.fromString("00000000-0000-0000-0000-000000000111");
            UUID principalId = UUID.fromString("00000000-0000-0000-0000-000000000113");
            context.getBean(SearchProjectionWriter.class)
                    .apply(generation, snapshot(spaceId, principalId, "联系 13800138000"));

            var candidates = context.getBean(VectorRetriever.class).retrieve(
                    "联系方式",
                    new RetrievalFilter(
                            Set.of(spaceId),
                            Set.of(new PrincipalRef(PrincipalType.USER, principalId)),
                            true, Instant.parse("2026-10-10T09:00:00Z"), Map.of()),
                    10);

            assertThat(candidates).hasSize(1);
            assertThat(MODEL_REQUEST_BODIES).hasSize(2);
            assertThat(MODEL_REQUEST_BODIES.getFirst())
                    .contains("[PHONE]")
                    .doesNotContain("13800138000");
            assertThat(readSource(generation, "version-wiring:node-1").path("embedding").size())
                    .isEqualTo(3);
        }
    }

    private static ConfigurableApplicationContext startContext() {
        return startContext(Map.of());
    }

    private static ConfigurableApplicationContext startContext(Map<String, Object> overrides) {
        Map<String, Object> properties = new HashMap<>(Map.ofEntries(
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
                Map.entry("rag.workers.enabled", "true"),
                Map.entry("rag.ingestion.worker.poll-delay", "PT1H"),
                Map.entry("rag.opensearch.worker.poll-delay", "PT1H"),
                Map.entry("rag.opensearch.endpoint", endpoint.toString()),
                Map.entry("rag.qa.hmac-key", "qa-wiring-key-32-bytes-minimum!!"),
                Map.entry("rag.retrieval.vector.enabled", "false"),
                Map.entry("rag.bootstrap-admin.username", "wiring-admin"),
                Map.entry("rag.bootstrap-admin.password", "wiring-admin-password"),
                Map.entry("rag.bootstrap-admin.display-name", "Wiring Admin"),
                Map.entry("spring.autoconfigure.exclude", String.join(",",
                        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
                        "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
                        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")),
                Map.entry("logging.level.root", "ERROR")));
        properties.putAll(overrides);
        return new SpringApplicationBuilder(RagApplication.class)
                .profiles("local")
                .initializers(application -> application.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("retrieval-wiring-test", properties)))
                .run();
    }

    private static ScoredCandidate candidate() {
        var candidate = new Candidate(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "node-1", "标题", "正文", new MarkdownLocator(List.of("章节"), "node-1"),
                RecallSource.LEXICAL, 1, 1.0);
        return new ScoredCandidate(candidate, 1.0, 1, EnumSet.of(RecallSource.LEXICAL));
    }

    private static RetrievalProjectionSnapshot snapshot(
            UUID spaceId, UUID principalId, String body) {
        UUID documentId = UUID.fromString("00000000-0000-0000-0000-000000000112");
        UUID versionId = UUID.nameUUIDFromBytes("version-wiring".getBytes(StandardCharsets.UTF_8));
        var node = new ProjectionNode(
                "version-wiring:node-1", UUID.randomUUID(), "node-1", "PARAGRAPH", body,
                List.of("章节"), 0, new MarkdownLocator(List.of("章节"), "node-1"), 0.9);
        return new RetrievalProjectionSnapshot(
                spaceId, documentId, versionId, "联系方式", 1, 1,
                Instant.parse("2026-10-10T08:00:00Z"),
                Instant.parse("2026-10-10T07:59:00Z"),
                Set.of(new ProjectionAclGrant("USER:" + principalId, null)),
                Set.of(), false, Map.of(), List.of(node), true);
    }

    private static void embeddingResponse(HttpExchange exchange) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        MODEL_REQUEST_BODIES.add(requestBody);
        int size = JSON.readTree(requestBody).path("input").size();
        var data = JSON.createArrayNode();
        for (int index = 0; index < size; index++) {
            data.addObject().put("index", index).putArray("embedding").add(0.1).add(0.2).add(0.3);
        }
        byte[] response = JSON.createObjectNode().set("data", data).toString()
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static com.fasterxml.jackson.databind.JsonNode readSource(String index, String id)
            throws Exception {
        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint.resolve(
                        "/" + index + "/_doc/" + id.replace(":", "%3A"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).path("_source");
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
