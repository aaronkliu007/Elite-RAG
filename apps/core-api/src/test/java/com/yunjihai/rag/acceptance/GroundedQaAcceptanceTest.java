package com.yunjihai.rag.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.yunjihai.rag.RagApplication;
import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.access.role.RoleAssignmentService;
import com.yunjihai.rag.access.role.SpaceRole;
import com.yunjihai.rag.api.idempotency.IdempotencyConflictException;
import com.yunjihai.rag.identity.local.LocalAuthenticationService;
import com.yunjihai.rag.identity.local.UserAdministrationService;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchClientFactory;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchIndexManager;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchProperties;
import com.yunjihai.rag.ingestion.CanonicalDocumentBody;
import com.yunjihai.rag.ingestion.CanonicalDocumentV1;
import com.yunjihai.rag.ingestion.CanonicalNode;
import com.yunjihai.rag.ingestion.IngestionJobService;
import com.yunjihai.rag.ingestion.ParserStatus;
import com.yunjihai.rag.ingestion.UploadCommand;
import com.yunjihai.rag.ingestion.UploadService;
import com.yunjihai.rag.knowledge.DocumentService;
import com.yunjihai.rag.knowledge.KnowledgeSpaceService;
import com.yunjihai.rag.qa.domain.QaStatus;
import com.yunjihai.rag.qa.web.QaController;
import com.yunjihai.rag.qa.web.QaDtos;
import com.yunjihai.rag.qa.web.QaSourceController;
import com.yunjihai.rag.retrieval.projection.ProjectionWorker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroundedQaAcceptanceTest {
    private static GroundedQaAcceptanceEnvironment environment;

    @BeforeAll
    static void start() {
        environment = new GroundedQaAcceptanceEnvironment();
    }

    @AfterAll
    static void stop() {
        if (environment != null) environment.close();
    }

    @Test
    void registersTheProtectedReviewEndpointInTheRealQaRuntime() {
        assertThat(environment.context.getBean(com.yunjihai.rag.qa.evaluation.EvaluationReviewService.class)).isNotNull();
        assertThat(environment.context.getBean(com.yunjihai.rag.qa.web.QaEvaluationReviewController.class)).isNotNull();
        assertThat(environment.context.getBean(com.yunjihai.rag.qa.web.QaEvaluationAdminController.class)).isNotNull();
    }

    @Test
    void answersAcrossEveryAuthorizedSpaceWithoutSendingUnauthorizedEvidenceAndReauthorizesSources() {
        var scenario = environment.seed("happy");
        var response = environment.qa.ask(environment.authentication(scenario.reader()),
                "qa-happy-" + scenario.suffix(), new QaDtos.AskQuestionRequest("苍穹验收知识如何执行？"));

        assertThat(response.status())
                .withFailMessage(() -> "retrieval audit: " + environment.retrievalAudit(response.requestId()))
                .isEqualTo(QaStatus.ANSWERED);
        assertThat(response.citations()).isNotEmpty();
        String modelRequest = environment.gateway.lastChatRequest();
        assertThat(modelRequest)
                .contains("允许空间甲", "允许空间乙")
                .doesNotContain("禁止空间机密标记");

        UUID evidenceId = response.citations().getFirst().evidenceIds().getFirst();
        var source = environment.sources.get(
                environment.authentication(scenario.reader()), response.requestId(), evidenceId);
        assertThat(source.documentId()).isNotNull();
        assertThat(source.location().coordinates()).isNotEmpty();

        UUID citedSpace = environment.jdbc.sql("""
                SELECT space_id FROM qa_evidence_snapshot
                WHERE request_id=:requestId AND evidence_id=:evidenceId
                """).param("requestId", response.requestId()).param("evidenceId", evidenceId)
                .query(UUID.class).single();
        environment.roles.revokeSpaceRole(scenario.admin(), citedSpace, scenario.reader(),
                SpaceRole.READER, Instant.now());
        assertThatThrownBy(() -> environment.sources.get(
                environment.authentication(scenario.reader()), response.requestId(), evidenceId))
                .isExactlyInstanceOf(AccessDeniedAsNotFoundException.class);
    }

    @Test
    void rejectsNegationDroppedFromAnAuthorizedExactQuote() {
        var scenario = environment.seed("negated-quote");
        environment.appendNegatedApprovalToAuthorizedDocuments(scenario);
        environment.gateway.truncatedNegatedQuote();
        int before = environment.gateway.chatCalls();

        var response = environment.qa.ask(environment.authentication(scenario.reader()),
                "qa-negated-" + scenario.suffix(), new QaDtos.AskQuestionRequest("苍穹验收知识如何执行？"));

        assertThat(response.status()).isEqualTo(QaStatus.REFUSED);
        assertThat(response.refusal()).isEqualTo("UNSUPPORTED_CLAIM");
        assertThat(environment.gateway.chatCalls() - before).isEqualTo(1);
        assertThat(environment.jdbc.sql("SELECT count(*) FROM qa_model_call_audit WHERE request_id=:id")
                .param("id", response.requestId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentIdempotentSubmissionInvokesChatOnlyOnce() throws Exception {
        var scenario = environment.seed("idempotent");
        environment.gateway.blockNextChat();
        int before = environment.gateway.chatCalls();
        var request = new QaDtos.AskQuestionRequest("苍穹验收知识如何执行？");
        String key = "qa-concurrent-" + scenario.suffix();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> environment.qa.ask(
                    environment.authentication(scenario.reader()), key, request));
            assertThat(environment.gateway.awaitBlockedChat()).isTrue();
            assertThatThrownBy(() -> environment.qa.ask(
                    environment.authentication(scenario.reader()), key, request))
                    .isInstanceOf(IdempotencyConflictException.class)
                    .extracting("reason")
                    .isEqualTo(IdempotencyConflictException.Reason.IN_PROGRESS);
            environment.gateway.releaseBlockedChat();
            assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(QaStatus.ANSWERED);
        }

        assertThat(environment.gateway.chatCalls() - before).isEqualTo(1);
    }
}

final class GroundedQaAcceptanceEnvironment implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INDEX = "chunks-grounded-qa-acceptance";
    private final GenericContainer<?> openSearch = new GenericContainer<>(
            DockerImageName.parse("opensearchproject/opensearch:3.8.0"))
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));
    final DynamicModelGateway gateway = new DynamicModelGateway();
    final ConfigurableApplicationContext context;
    final LocalAuthenticationService authentication;
    final UserAdministrationService users;
    final RoleAssignmentService roles;
    final KnowledgeSpaceService spaces;
    final UploadService uploads;
    final IngestionJobService ingestion;
    final DocumentService documents;
    final ProjectionWorker projections;
    final OpenSearchIndexManager indexes;
    final QaController qa;
    final QaSourceController sources;
    final JdbcClient jdbc;

    GroundedQaAcceptanceEnvironment() {
        LocalFoundationInfrastructure.start();
        openSearch.start();
        gateway.launch();
        URI endpoint = URI.create("http://" + openSearch.getHost() + ":" + openSearch.getMappedPort(9200));
        try (var connection = new OpenSearchClientFactory().create(new OpenSearchProperties(endpoint, null, null))) {
            var manager = new OpenSearchIndexManager(connection.client());
            manager.createGeneration(INDEX, OptionalInt.empty());
            manager.switchAliases(null, INDEX);
        } catch (Exception failure) {
            closeInfrastructure();
            throw new IllegalStateException("GROUND_QA_ACCEPTANCE_OPENSEARCH_SETUP_FAILED", failure);
        }
        context = startContext(endpoint);
        authentication = context.getBean(LocalAuthenticationService.class);
        users = context.getBean(UserAdministrationService.class);
        roles = context.getBean(RoleAssignmentService.class);
        spaces = context.getBean(KnowledgeSpaceService.class);
        uploads = context.getBean("runtimeUploadService", UploadService.class);
        ingestion = context.getBean(IngestionJobService.class);
        documents = context.getBean(DocumentService.class);
        projections = context.getBean(ProjectionWorker.class);
        indexes = context.getBean(OpenSearchIndexManager.class);
        qa = context.getBean(QaController.class);
        sources = context.getBean(QaSourceController.class);
        jdbc = context.getBean(JdbcClient.class);
    }

    Scenario seed(String label) {
        return seed(label, false);
    }

    Scenario seedUnsafe(String label) {
        return seed(label, true);
    }

    private Scenario seed(String label, boolean unsafeEvidence) {
        gateway.answerNormally();
        String suffix = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        UUID admin = authentication.authenticate(
                "qa-acceptance-admin", "qa-acceptance-password".toCharArray(), "127.0.0.1").userId();
        UUID editor = createUser("editor-" + suffix, admin);
        UUID reviewer = createUser("reviewer-" + suffix, admin);
        UUID reader = createUser("reader-" + suffix, admin);
        UUID forbiddenReader = createUser("forbidden-" + suffix, admin);
        Instant now = Instant.now();
        UUID spaceA = spaces.createSpace("允许空间甲-" + suffix, "自动验收", admin, now).id();
        UUID spaceB = spaces.createSpace("允许空间乙-" + suffix, "自动验收", admin, now).id();
        UUID forbiddenSpace = spaces.createSpace("禁止空间-" + suffix, "自动验收", admin, now).id();
        for (UUID space : List.of(spaceA, spaceB, forbiddenSpace)) {
            roles.grantSpaceRole(admin, space, editor, SpaceRole.EDITOR, now);
            roles.grantSpaceRole(admin, space, reviewer, SpaceRole.REVIEWER_PUBLISHER, now);
        }
        roles.grantSpaceRole(admin, spaceA, reader, SpaceRole.READER, now);
        roles.grantSpaceRole(admin, spaceB, reader, SpaceRole.READER, now);
        roles.grantSpaceRole(admin, forbiddenSpace, forbiddenReader, SpaceRole.READER, now);

        publish(spaceA, "允许空间甲 Markdown", "allowed-a.md", MediaType.TEXT_MARKDOWN,
                "# 允许空间甲".getBytes(StandardCharsets.UTF_8), "允许空间甲", editor, reviewer,
                suffix + "-a", unsafeEvidence);
        publish(spaceA, "允许空间甲 PDF", "allowed-a.pdf", MediaType.APPLICATION_PDF,
                "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII), "允许空间甲", editor, reviewer,
                suffix + "-b", false);
        publish(spaceB, "允许空间乙 PNG", "allowed-b.png", MediaType.IMAGE_PNG,
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0},
                "允许空间乙", editor, reviewer, suffix + "-c", false);
        publish(forbiddenSpace, "禁止空间机密", "forbidden.md", MediaType.TEXT_MARKDOWN,
                "# 禁止空间".getBytes(StandardCharsets.UTF_8), "禁止空间机密标记", editor, reviewer,
                suffix + "-forbidden", false);
        for (int attempt = 0; attempt < 10 && projections.runOnce(
                "qa-acceptance-projection", OpenSearchIndexManager.WRITE_ALIAS) > 0; attempt++) {
            // Drain all immutable projection events before querying.
        }
        indexes.refresh(OpenSearchIndexManager.READ_ALIAS);
        return new Scenario(suffix, admin, reader, List.of(spaceA, spaceB), forbiddenSpace);
    }

    UsernamePasswordAuthenticationToken authentication(UUID userId) {
        return new UsernamePasswordAuthenticationToken(userId.toString(), "n/a", List.of());
    }

    void appendNegatedApprovalToAuthorizedDocuments(Scenario scenario) {
        var publisher = context.getBean(com.yunjihai.rag.retrieval.projection.ProjectionChangePublisher.class);
        var transactions = new org.springframework.transaction.support.TransactionTemplate(
                context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        for (UUID space : scenario.allowedSpaces()) {
            var documentIds = jdbc.sql("SELECT id FROM document WHERE space_id=:space")
                    .param("space", space).query(UUID.class).list();
            for (UUID document : documentIds) {
                jdbc.sql("""
                        UPDATE canonical_node SET body_text=body_text || '。本方案未批准。'
                        WHERE version_id=(SELECT current_published_version_id FROM document WHERE id=:document)
                        """).param("document", document).update();
                transactions.executeWithoutResult(status -> publisher.withDocumentChangeLock(document, () -> {
                    publisher.documentAclChanged(space, document, Instant.now()); return null;
                }));
            }
        }
        for (int attempt = 0; attempt < 10 && projections.runOnce(
                "qa-negated-projection", OpenSearchIndexManager.WRITE_ALIAS) > 0; attempt++) {
            // Drain synthetic projection events before querying.
        }
        indexes.refresh(OpenSearchIndexManager.READ_ALIAS);
    }

    String retrievalAudit(UUID requestId) {
        return jdbc.sql("""
                SELECT metadata::text FROM audit_event
                WHERE action='RETRIEVE' AND resource_type='RETRIEVAL' AND resource_id=:requestId
                ORDER BY occurred_at DESC LIMIT 1
                """).param("requestId", requestId).query(String.class).optional().orElse("missing");
    }

    private UUID createUser(String username, UUID admin) {
        return users.createUser(username, username, ("acceptance-" + username).toCharArray(), admin).id();
    }

    private void publish(UUID spaceId, String title, String filename, MediaType mediaType,
                         byte[] bytes, String marker, UUID editor, UUID reviewer, String key,
                         boolean unsafeEvidence) {
        var version = uploads.accept(new UploadCommand(spaceId, title, "内部", List.of("苍穹验收"),
                filename, mediaType, bytes.length, new ByteArrayInputStream(bytes), editor),
                "upload-" + key);
        var lease = ingestion.claimNext("qa-acceptance-parser").orElseThrow();
        List<String> rules = List.of(
                "服务申请先登记需求、责任人和完成日期，再由项目经理确认范围",
                "技术评审应核对接口、数据权限和回滚预案，并保存评审结论",
                "上线复盘需要汇总验证记录、风险处置和改进责任，形成可追溯台账");
        String formatRule = mediaType.equals(MediaType.TEXT_MARKDOWN)
                ? "Markdown 文档按章节路径定位，保留标题层级和段落锚点"
                : mediaType.equals(MediaType.APPLICATION_PDF)
                        ? "PDF 文档按原始页码复核，保留版面顺序和分页定位"
                        : "PNG 图像按 OCR 区域复核，保留页内坐标和识别置信度";
        List<CanonicalNode> nodes = new ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            String sensitiveBlock = unsafeEvidence && index == 1 ? """
                    -----BEGIN PRIVATE KEY-----
                    abcdefghijklmnopqrstuvwxyz
                    -----END PRIVATE KEY-----
                    """ : "";
            nodes.add(new CanonicalNode("node-" + index, "PARAGRAPH",
                    "苍穹验收知识 " + marker + "：" + rules.get(index - 1)
                            + "。" + formatRule + "。" + sensitiveBlock
                            + "唯一编号" + key + "-" + index + "。",
                    null, mediaType.equals(MediaType.TEXT_MARKDOWN) ? null : index,
                    null, null, 0.99));
        }
        ingestion.complete(lease, new CanonicalDocumentV1("canonical-document-v1", lease.jobId(),
                ParserStatus.SUCCEEDED, new CanonicalDocumentBody(filename, mediaType.toString(), 3, nodes),
                List.of()));
        Instant now = Instant.now();
        documents.submitForReview(version.documentId(), version.versionId(), editor, now);
        documents.publish(version.documentId(), version.versionId(), reviewer, now.plusMillis(1));
    }

    private ConfigurableApplicationContext startContext(URI openSearchEndpoint) {
        Map<String, Object> properties = Map.ofEntries(
                Map.entry("spring.main.web-application-type", "none"),
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
                Map.entry("rag.opensearch.endpoint", openSearchEndpoint.toString()),
                Map.entry("rag.retrieval.vector.enabled", "false"),
                Map.entry("rag.workers.enabled", "false"),
                Map.entry("rag.parser.endpoint", "http://127.0.0.1:9"),
                Map.entry("rag.qa.hmac-key", "qa-acceptance-hmac-key-32-bytes!!"),
                Map.entry("rag.ai.enabled", "true"),
                Map.entry("rag.ai.provider", "approved-openai-compatible"),
                Map.entry("rag.ai.config-version", "acceptance-config-v1"),
                Map.entry("rag.ai.endpoint", gateway.baseUrl()),
                Map.entry("rag.ai.approved-hosts", URI.create(gateway.baseUrl()).getHost()),
                Map.entry("rag.ai.api-key", "qa-acceptance-test-key"),
                Map.entry("rag.ai.embedding-model", "acceptance-embedding"),
                Map.entry("rag.ai.reranker-model", "acceptance-reranker"),
                Map.entry("rag.ai.chat-model", "acceptance-chat"),
                Map.entry("rag.ai.connect-timeout", "PT0.2S"),
                Map.entry("rag.ai.response-timeout", "PT0.5S"),
                Map.entry("rag.ai.total-timeout", "PT1S"),
                Map.entry("rag.ai.max-input-tokens", "8000"),
                Map.entry("rag.ai.max-output-tokens", "2000"),
                Map.entry("rag.ai.max-concurrency", "4"),
                Map.entry("rag.ai.daily-deployment-token-limit", "1000000"),
                Map.entry("rag.ai.allow-insecure-loopback-for-tests", "true"),
                Map.entry("rag.bootstrap-admin.username", "qa-acceptance-admin"),
                Map.entry("rag.bootstrap-admin.password", "qa-acceptance-password"),
                Map.entry("rag.bootstrap-admin.display-name", "QA 验收管理员"),
                Map.entry("logging.level.root", "ERROR"));
        return new SpringApplicationBuilder(RagApplication.class).profiles("local")
                .initializers(application -> application.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("grounded-qa-acceptance", properties)))
                .run();
    }

    @Override
    public void close() {
        if (context != null) context.close();
        closeInfrastructure();
    }

    private void closeInfrastructure() {
        gateway.close();
        if (openSearch.isRunning()) openSearch.stop();
        LocalFoundationInfrastructure.stop();
    }

    record Scenario(String suffix, UUID admin, UUID reader, List<UUID> allowedSpaces, UUID forbiddenSpace) { }

    static final class DynamicModelGateway implements AutoCloseable, ResponseTransformerV2 {
        private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.ANSWER);
        private final AtomicInteger chatCalls = new AtomicInteger();
        private final List<String> chatRequests = java.util.Collections.synchronizedList(new ArrayList<>());
        private volatile CountDownLatch entered = new CountDownLatch(0);
        private volatile CountDownLatch release = new CountDownLatch(0);
        private WireMockServer server;

        void launch() {
            server = new WireMockServer(options().dynamicPort().extensions(this));
            server.start();
            server.stubFor(post(urlPathMatching("/v1/.*")).willReturn(aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withTransformers(getName())));
        }

        String baseUrl() { return server.baseUrl(); }
        int chatCalls() { return chatCalls.get(); }
        String lastChatRequest() { return chatRequests.getLast(); }
        void answerNormally() { mode.set(Mode.ANSWER); entered = new CountDownLatch(0); release = new CountDownLatch(0); }
        void invalidCitation() { mode.set(Mode.INVALID_CITATION); }
        void truncatedNegatedQuote() { mode.set(Mode.NEGATED_EXTRACT); }
        void rateLimit() { mode.set(Mode.RATE_LIMIT); }
        void timeout() { mode.set(Mode.TIMEOUT); }
        void blockNextChat() { entered = new CountDownLatch(1); release = new CountDownLatch(1); }
        boolean awaitBlockedChat() throws InterruptedException { return entered.await(5, TimeUnit.SECONDS); }
        void releaseBlockedChat() { release.countDown(); }

        @Override
        public String getName() { return "grounded-qa-dynamic-model"; }

        @Override
        public Response transform(Response response, ServeEvent event) {
            try {
                String path = event.getRequest().getUrl();
                JsonNode request = JSON.readTree(event.getRequest().getBodyAsString());
                if (path.equals("/v1/rerank")) {
                    var results = JSON.createArrayNode();
                    int rank = 0;
                    for (JsonNode document : request.path("documents")) {
                        results.addObject().put("candidate_id", document.path("candidate_id").asText())
                                .put("score", 1.0 - rank++ * 0.01);
                    }
                    return Response.Builder.like(response).status(200)
                            .body(JSON.createObjectNode().set("results", results).toString()).build();
                }
                if (!path.equals("/v1/chat/completions")) {
                    return Response.Builder.like(response).status(404).body("{}").build();
                }
                chatCalls.incrementAndGet();
                chatRequests.add(event.getRequest().getBodyAsString());
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
                if (mode.get() == Mode.RATE_LIMIT) {
                    return Response.Builder.like(response).status(429).body("{\"error\":\"limited\"}").build();
                }
                JsonNode payload = JSON.readTree(request.path("messages").get(1).path("content").asText());
                JsonNode evidence = payload.path("evidence").get(0);
                String evidenceId = mode.get() == Mode.INVALID_CITATION
                        ? UUID.randomUUID().toString() : evidence.path("evidenceId").asText();
                String quote = evidence.path("excerpt").asText();
                if (mode.get() == Mode.NEGATED_EXTRACT) {
                    if (!quote.contains("未批准")) throw new IllegalStateException("synthetic negated evidence missing");
                    quote = "批准";
                }
                String content = JSON.createObjectNode()
                        .put("decision", "ANSWER")
                        .put("answer", quote)
                        .set("claims", JSON.createArrayNode().add(JSON.createObjectNode()
                                .put("claimId", UUID.randomUUID().toString())
                                .put("text", quote)
                                .set("supports", JSON.createArrayNode().add(JSON.createObjectNode()
                                        .put("evidenceId", evidenceId).put("quote", quote)))))
                        .toString();
                var bodyNode = JSON.createObjectNode();
                bodyNode.put("id", "qa-acceptance-provider");
                bodyNode.set("choices", JSON.createArrayNode().add(JSON.createObjectNode()
                        .set("message", JSON.createObjectNode().put("content", content))));
                bodyNode.set("usage", JSON.createObjectNode().put("prompt_tokens", 100)
                        .put("completion_tokens", 20));
                String body = bodyNode.toString();
                var builder = Response.Builder.like(response).status(200).body(body);
                if (mode.get() == Mode.TIMEOUT) builder.incrementInitialDelay(1_500);
                return builder.build();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Response.Builder.like(response).status(503).body("{}").build();
            } catch (Exception failure) {
                return Response.Builder.like(response).status(500).body("{}").build();
            }
        }

        @Override public boolean applyGlobally() { return false; }
        @Override public void close() { if (server != null && server.isRunning()) server.stop(); }
        enum Mode { ANSWER, INVALID_CITATION, RATE_LIMIT, TIMEOUT, NEGATED_EXTRACT }
    }
}
