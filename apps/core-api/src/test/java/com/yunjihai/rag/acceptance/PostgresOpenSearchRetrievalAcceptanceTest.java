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
import com.yunjihai.rag.retrieval.embedding.EmbeddingPort;
import com.yunjihai.rag.retrieval.embedding.EmbeddingUnavailableException;
import com.yunjihai.rag.retrieval.projection.ProjectionChangePublisher;
import com.yunjihai.rag.retrieval.projection.ProjectionWorker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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
import java.util.HashMap;
import java.util.Set;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private static final AtomicInteger EMBEDDING_CALLS = new AtomicInteger();

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
    @EnabledIfEnvironmentVariable(named = "RAG_GOLDEN_DIAGNOSTIC", matches = "true")
    void goldenConflictCaseRetrievesBothSidesAndRefusesConflictingEvidence() throws Exception {
        try (var context = startContext(Map.ofEntries(
                Map.entry("rag.qa.evaluation.fixture.enabled", "true"),
                Map.entry("rag.qa.hmac-key", "synthetic-test-only-hmac-key-32-bytes")))) {
            ensureGoldenIndex(context);
            var dataset = context.getBean(com.yunjihai.rag.qa.evaluation.EvaluationDataset.class);
            var item = dataset.cases().stream().filter(value -> value.id().equals(
                    UUID.fromString("30000000-0000-7000-8000-000000000034"))).findFirst().orElseThrow();
            var spaces = context.getBean(com.yunjihai.rag.qa.AuthorizedSpaceScopePort.class)
                    .resolve(item.evaluationActorId(), Instant.now());
            var pack = context.getBean(TrustedRetrievalService.class).retrieve(new RetrievalQuery(
                    UUID.randomUUID(), item.evaluationActorId(), spaces, item.question(), Map.of(), 10));
            assertThat(pack.evidence()).anySatisfy(evidence -> assertThat(evidence.title()).contains("冲突证据 A"));
            assertThat(pack.evidence()).anySatisfy(evidence -> assertThat(evidence.title()).contains("冲突证据 B"));
            assertThat(new com.yunjihai.rag.qa.conflict.DeterministicEvidenceConflictDetector()
                    .hasConflict(pack.evidence())).isTrue();
            UUID requestId = UUID.randomUUID();
            var outcome = context.getBean(com.yunjihai.rag.qa.GroundedQaService.class).ask(
                    new com.yunjihai.rag.qa.AskQuestionCommand(requestId, item.evaluationActorId(), item.question()));
            assertThat(outcome).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                    refused -> assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.CONFLICTING_EVIDENCE));
            assertThat(context.getBean(JdbcClient.class).sql("SELECT count(*) FROM qa_model_call_audit WHERE request_id = :id")
                    .param("id", requestId).query(Long.class).single()).isZero();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RAG_DEEPSEEK_GOLDEN_TEST", matches = "true")
    void evaluatesSyntheticGoldenDatasetWithLiveDeepSeekWithoutAssertingBusinessQuality() throws Exception {
        try (var context = startContext(Map.ofEntries(
                Map.entry("rag.test.counting-embedding", "false"),
                Map.entry("rag.qa.evaluation.fixture.enabled", "true"),
                Map.entry("rag.ai.enabled", "true"),
                Map.entry("rag.ai.provider", "deepseek"),
                Map.entry("rag.ai.config-version", "deepseek-golden-test-v1"),
                Map.entry("rag.ai.endpoint", "https://api.deepseek.com"),
                Map.entry("rag.ai.approved-hosts", "api.deepseek.com"),
                Map.entry("rag.ai.api-key", System.getenv("RAG_AI_API_KEY")),
                Map.entry("rag.ai.embedding-model", ""),
                Map.entry("rag.ai.reranker-model", ""),
                Map.entry("rag.ai.chat-model", "deepseek-flash"),
                Map.entry("rag.ai.connect-timeout", "PT5S"),
                Map.entry("rag.ai.response-timeout", "PT45S"),
                Map.entry("rag.ai.total-timeout", "PT55S"),
                Map.entry("rag.qa.hmac-key", "synthetic-test-only-hmac-key-32-bytes")))) {
            ensureGoldenIndex(context);
            var dataset = context.getBean(com.yunjihai.rag.qa.evaluation.EvaluationDataset.class);
            assertThat(dataset.cases()).hasSize(50);
            var result = context.getBean(com.yunjihai.rag.qa.evaluation.QaEvaluationUseCase.class)
                    .run(dataset.id(), "synthetic-live", "qa-policy-v1", "deepseek-golden-test-v1");
            assertThat(result.metrics()).isNotNull();
            System.out.println("SYNTHETIC_GOLDEN_RESULT cases=" + dataset.cases().size()
                    + " metrics=" + result.metrics() + " gate=" + result.assessment());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RAG_DEEPSEEK_LIVE_TEST", matches = "true")
    void liveDeepSeekUsesRealRetrievalEvidenceAndAllowsReviewedRequirementAdoption() throws Exception {
        try (var context = startContext(Map.ofEntries(
                Map.entry("rag.test.counting-embedding", "false"),
                Map.entry("rag.ai.enabled", "true"),
                Map.entry("rag.ai.provider", "deepseek"),
                Map.entry("rag.ai.config-version", "deepseek-live-acceptance-v1"),
                Map.entry("rag.ai.endpoint", "https://api.deepseek.com"),
                Map.entry("rag.ai.approved-hosts", "api.deepseek.com"),
                Map.entry("rag.ai.api-key", System.getenv("RAG_AI_API_KEY")),
                Map.entry("rag.ai.embedding-model", ""),
                Map.entry("rag.ai.reranker-model", ""),
                Map.entry("rag.ai.chat-model", "deepseek-flash"),
                Map.entry("rag.ai.connect-timeout", "PT5S"),
                Map.entry("rag.ai.response-timeout", "PT45S"),
                Map.entry("rag.ai.total-timeout", "PT55S"),
                Map.entry("rag.qa.hmac-key", "synthetic-test-only-hmac-key-32-bytes")))) {
            var jdbc = context.getBean(JdbcClient.class);
            UUID user = UUID.randomUUID(), space = UUID.randomUUID(), document = UUID.randomUUID();
            UUID version = UUID.randomUUID(), acl = UUID.randomUUID();
            seedPublishedDocument(jdbc, user, space, document, version, acl);
            jdbc.sql("UPDATE canonical_node SET body_text = :text WHERE version_id = :version")
                    .param("text", "Kubernetes 灰度部署方案先部署测试版本，再验证并在异常时回滚。")
                    .param("version", version).update();
            var allDocuments = new java.util.ArrayList<UUID>();
            allDocuments.add(document);
            var extraTexts = java.util.List.of(
                    "Kubernetes 灰度部署使用隔离环境验证服务启动状态。",
                    "Kubernetes 灰度部署应由运维人员检查资源使用情况。",
                    "Kubernetes 灰度部署安排专人记录灰度观察结果。",
                    "Kubernetes 灰度部署完成验证后才扩大服务流量。",
                    "Kubernetes 灰度部署发现错误时应及时停止流量切换。",
                    "Kubernetes 灰度部署保留原镜像用于故障恢复。",
                    "Kubernetes 灰度部署需要在变更窗口通知项目团队。");
            for (String text : extraTexts) {
                UUID extraDocument = UUID.randomUUID(), extraVersion = UUID.randomUUID();
                seedAdditionalPublishedDocument(jdbc, user, space, extraDocument, extraVersion, text);
                allDocuments.add(extraDocument);
            }
            var transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            var publisher = context.getBean(ProjectionChangePublisher.class);
            for (UUID item : allDocuments) {
                transactions.executeWithoutResult(status -> publisher.withDocumentChangeLock(item, () -> {
                    publisher.documentAclChanged(space, item, Instant.now()); return null;
                }));
            }
            assertThat(context.getBean(ProjectionWorker.class).runOnce("live-worker", OpenSearchIndexManager.WRITE_ALIAS))
                    .isEqualTo(8);
            context.getBean(com.yunjihai.rag.access.role.RoleAssignmentStore.class).grantSpaceRole(space, user,
                    com.yunjihai.rag.access.role.SpaceRole.SPACE_ADMIN, user, Instant.now());
            var hits = context.getBean(TrustedRetrievalService.class).retrieve(new RetrievalQuery(
                    UUID.randomUUID(), user, Set.of(space), "Kubernetes 灰度部署方案", Map.of(), 10));
            assertThat(hits.evidence()).hasSizeGreaterThanOrEqualTo(8);
            var answer = context.getBean(com.yunjihai.rag.qa.GroundedQaService.class).ask(
                    new com.yunjihai.rag.qa.AskQuestionCommand(UUID.randomUUID(), user, "Kubernetes 灰度部署方案是什么？"));
            assertThat(answer).isInstanceOf(com.yunjihai.rag.qa.domain.QaOutcome.Answered.class);
            UUID missingRequest = UUID.randomUUID();
            var refusal = context.getBean(com.yunjihai.rag.qa.GroundedQaService.class).ask(
                    new com.yunjihai.rag.qa.AskQuestionCommand(missingRequest, user, "QZXV123987654不存在的虚构资料是什么？"));
            assertThat(refusal).isInstanceOf(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class);
            assertThat(jdbc.sql("SELECT count(*) FROM qa_model_call_audit WHERE request_id = :id")
                    .param("id", missingRequest).query(Long.class).single()).isZero();
            var commands = context.getBean(com.yunjihai.rag.solution.application.SolutionCommandService.class);
            var created = commands.create(user, "live-create", new com.yunjihai.rag.solution.application.SolutionCommandService.CreateInput(
                    space, "虚构验收方案", java.util.List.of(new com.yunjihai.rag.solution.domain.RequirementItem(
                    UUID.randomUUID(), com.yunjihai.rag.solution.domain.RequirementCategory.UNKNOWN, "待明确", java.util.List.of())), 0));
            var selected = new com.yunjihai.rag.knowledge.DocumentVersionRef(document, version);
            var command = new com.yunjihai.rag.solution.application.DocumentRequirementProposalService.Command(
                    user, created.solutionId(), created.revision(), "live-document-proposal", "Kubernetes 灰度部署方案", java.util.List.of(selected));
            var candidate = context.getBean(com.yunjihai.rag.solution.application.DocumentRequirementProposalService.class).generate(command);
            assertThat(candidate.status()).isEqualTo(com.yunjihai.rag.solution.proposal.ProposalStatus.SUCCEEDED);
            var items = new com.fasterxml.jackson.databind.ObjectMapper().readTree(candidate.resultJson().orElseThrow()).path("items");
            assertThat(items.isArray()).isTrue();
            assertThat(items.toString()).contains(document.toString());
            var snapshot = context.getBean(com.yunjihai.rag.infrastructure.postgres.JdbcSolutionProposalEvidenceStore.class)
                    .find(candidate.id()).orElseThrow();
            var preview = context.getBean(com.yunjihai.rag.solution.application.DocumentRequirementEvidencePreviewService.class)
                    .preview(command, snapshot.sources().getFirst().evidenceId());
            assertThat(preview.text()).contains("Kubernetes 灰度部署");
            var adopted = context.getBean(com.yunjihai.rag.solution.application.DocumentRequirementProposalAdoptionService.class)
                    .adopt(command, "live-adopt");
            assertThat(adopted.revision()).isEqualTo(created.revision() + 1);
            jdbc.sql("UPDATE acl_entry SET revoked_at = :at WHERE id = :id")
                    .param("at", OffsetDateTime.now(ZoneOffset.UTC)).param("id", acl).update();
            assertThatThrownBy(() -> context.getBean(com.yunjihai.rag.solution.application.DocumentRequirementProposalAdoptionService.class)
                    .adopt(command, "live-adopt"))
                    .isInstanceOf(com.yunjihai.rag.access.AccessDeniedAsNotFoundException.class);
        }
    }

    @Test
    void filtersStaleCandidateAfterRevocation() throws Exception {
        EMBEDDING_CALLS.set(0);
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
                    UUID.randomUUID(), user, Set.of(space), "Kubernetes 灰度部署方案", Map.of(), 8);
            var first = service.retrieve(query);

            assertThat(first.evidence()).hasSize(1);
            assertThat(first.evidence().getFirst().documentId()).isEqualTo(document);
            assertThat(first.evidence().getFirst().locator()).isInstanceOf(MarkdownLocator.class);
            var selected = new com.yunjihai.rag.knowledge.DocumentVersionRef(document, version);
            var documentEvidence = context.getBean(com.yunjihai.rag.solution.application.DocumentRequirementEvidenceService.class);
            var scoped = documentEvidence.select(UUID.randomUUID(), user, space, "Kubernetes 灰度部署方案", java.util.List.of(selected));
            assertThat(scoped.evidence()).hasSize(1).allSatisfy(item -> {
                assertThat(item.documentId()).isEqualTo(document);
                assertThat(item.versionId()).isEqualTo(version);
            });
            assertThat(scoped.documentsWithoutEvidence()).isEmpty();
            context.getBean(com.yunjihai.rag.access.role.RoleAssignmentStore.class).grantSpaceRole(space, user,
                    com.yunjihai.rag.access.role.SpaceRole.SPACE_ADMIN, user, Instant.now());
            var commands = context.getBean(com.yunjihai.rag.solution.application.SolutionCommandService.class);
            var created = commands.create(user, "acceptance-create", new com.yunjihai.rag.solution.application.SolutionCommandService.CreateInput(
                    space, "真实检索文档需求", java.util.List.of(new com.yunjihai.rag.solution.domain.RequirementItem(
                            UUID.randomUUID(), com.yunjihai.rag.solution.domain.RequirementCategory.UNKNOWN, "待明确", java.util.List.of())), 0));
            var modelCalls = new AtomicInteger();
            com.yunjihai.rag.solution.model.SolutionModelPort fakeModel = new com.yunjihai.rag.solution.model.SolutionModelPort() {
                public <T> com.yunjihai.rag.solution.model.SolutionModelResponse<T> execute(
                        com.yunjihai.rag.solution.model.SolutionModelRequest request, Class<T> type) {
                    modelCalls.incrementAndGet();
                    assertThat(request.prompt().version()).isEqualTo("v2");
                    var item = request.evidence().getFirst();
                    var generated = new com.yunjihai.rag.solution.model.GeneratedRequirementProposal(java.util.List.of(
                            new com.yunjihai.rag.solution.model.GeneratedRequirementProposal.Item(UUID.randomUUID(), "GOAL", "支持灰度部署",
                                    java.util.List.of(new com.yunjihai.rag.solution.domain.RequirementSource(
                                            new com.yunjihai.rag.knowledge.DocumentVersionRef(item.documentId(), item.versionId()), item.locator())))));
                    return new com.yunjihai.rag.solution.model.SolutionModelResponse<>(type, type.cast(generated),
                            "test-model", new com.yunjihai.rag.solution.generation.ModelUsage(1, 1, 0, 1));
                }
            };
            var proposals = new com.yunjihai.rag.solution.application.DocumentRequirementProposalService(
                    context.getBean(com.yunjihai.rag.infrastructure.postgres.JdbcSolutionRepository.class),
                    context.getBean(com.yunjihai.rag.infrastructure.postgres.JdbcRequirementVersionRepository.class),
                    context.getBean(com.yunjihai.rag.solution.application.SolutionAccessService.class),
                    context.getBean(com.yunjihai.rag.solution.application.SolutionSourceAccess.class),
                    context.getBean(com.yunjihai.rag.infrastructure.postgres.JdbcSolutionDocumentSourcePort.class), documentEvidence,
                    context.getBean(com.yunjihai.rag.infrastructure.postgres.JdbcSolutionProposalAttemptStore.class),
                    context.getBean(com.yunjihai.rag.infrastructure.postgres.JdbcSolutionProposalEvidenceStore.class),
                    fakeModel, java.time.Clock.systemUTC(), true);
            var documentCommand = new com.yunjihai.rag.solution.application.DocumentRequirementProposalService.Command(user,
                    created.solutionId(), created.revision(), "acceptance-document-proposal", "Kubernetes 灰度部署方案", java.util.List.of(selected));
            var candidate = proposals.generate(documentCommand);
            assertThat(candidate.status()).isEqualTo(com.yunjihai.rag.solution.proposal.ProposalStatus.SUCCEEDED);
            assertThat(proposals.generate(documentCommand).id()).isEqualTo(candidate.id());
            assertThat(modelCalls).hasValue(1);
            var snapshot = context.getBean(com.yunjihai.rag.infrastructure.postgres.JdbcSolutionProposalEvidenceStore.class)
                    .find(candidate.id()).orElseThrow();
            var preview = new com.yunjihai.rag.solution.application.DocumentRequirementEvidencePreviewService(proposals,
                    context.getBean(com.yunjihai.rag.infrastructure.postgres.JdbcSolutionProposalEvidenceStore.class),
                    context.getBean(com.yunjihai.rag.infrastructure.postgres.JdbcDocumentEvidenceContent.class));
            assertThat(preview.preview(documentCommand, snapshot.sources().getFirst().evidenceId()).text())
                    .isEqualTo(("Kubernetes 灰度部署方案与回滚步骤" + "甲".repeat(1160)
                            + "[EMAIL]" + "乙".repeat(80)).substring(0, 1200));
            var adoption = context.getBean(com.yunjihai.rag.solution.application.DocumentRequirementProposalAdoptionService.class);
            var adopted = adoption.adopt(documentCommand, "acceptance-adopt");
            assertThat(adopted.revision()).isEqualTo(created.revision() + 1);
            assertThat(adoption.adopt(documentCommand, "acceptance-adopt")).isEqualTo(adopted);
            assertThat(first.degradationReasons()).contains(
                    DegradationReason.VECTOR_UNAVAILABLE,
                    DegradationReason.RERANKER_UNAVAILABLE);
            assertThat(EMBEDDING_CALLS).hasValue(0);

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
                    UUID.randomUUID(), user, Set.of(space), "Kubernetes 灰度部署方案", Map.of(), 8));
            assertThat(afterRevocation.stats().fusedCount()).isEqualTo(1);
            assertThat(afterRevocation.stats().reauthorizedCount()).isZero();
            assertThat(afterRevocation.evidence()).isEmpty();
            assertThatThrownBy(() -> documentEvidence.select(UUID.randomUUID(), user, space,
                    "Kubernetes 灰度部署方案", java.util.List.of(selected)))
                    .isInstanceOf(com.yunjihai.rag.access.AccessDeniedAsNotFoundException.class);
            assertThatThrownBy(() -> adoption.adopt(documentCommand, "acceptance-adopt"))
                    .isInstanceOf(com.yunjihai.rag.access.AccessDeniedAsNotFoundException.class);
            assertThat(EMBEDDING_CALLS).hasValue(0);
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
                VALUES (:version, 'node-1', 'PARAGRAPH', :body,
                        :parent, 1, CAST('null' AS jsonb), CAST('null' AS jsonb), 0.99, 1)
                """).param("version", version).param("body", "Kubernetes 灰度部署方案与回滚步骤"
                        + "甲".repeat(1160) + "test@example.com" + "乙".repeat(80))
                .param("parent", null, Types.VARCHAR).update();
        jdbc.sql("""
                INSERT INTO acl_entry
                    (id, space_id, document_id, scope, principal_type, principal_id)
                VALUES (:id, :space, :document, 'SPACE', 'USER', :user)
                """).param("id", acl).param("space", space)
                .param("document", null, Types.OTHER).param("user", user).update();
    }

    private static void seedAdditionalPublishedDocument(JdbcClient jdbc, UUID user, UUID space,
            UUID document, UUID version, String text) {
        var at = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        jdbc.sql("INSERT INTO document (id, space_id, title, archived, revision) VALUES (:id, :space, '虚构部署资料', false, 1)")
                .param("id", document).param("space", space).update();
        jdbc.sql("""
                INSERT INTO document_version (id, document_id, version_number, source_blob_id, status,
                        parse_status, created_by, status_changed_at)
                VALUES (:id, :document, 1, 'synthetic-blob', 'PUBLISHED', 'SUCCEEDED', :user, :at)
                """).param("id", version).param("document", document).param("user", user).param("at", at).update();
        jdbc.sql("UPDATE document SET current_published_version_id = :version WHERE id = :document")
                .param("version", version).param("document", document).update();
        jdbc.sql("""
                INSERT INTO canonical_document (version_id, source_filename, media_type, page_count, parsed_at)
                VALUES (:version, 'synthetic.md', 'text/markdown', 1, :at)
                """).param("version", version).param("at", at).update();
        jdbc.sql("""
                INSERT INTO canonical_node (version_id, node_id, kind, body_text, parent_node_id, page,
                        bounding_box, table_cells, confidence, stable_order)
                VALUES (:version, 'node-1', 'PARAGRAPH', :text, NULL, 1,
                        CAST('null' AS jsonb), CAST('null' AS jsonb), 0.99, 1)
                """).param("version", version).param("text", text).update();
    }

    private static void ensureGoldenIndex(ConfigurableApplicationContext context) throws Exception {
        context.getBean(ProjectionWorker.class).runOnce("golden-worker", OpenSearchIndexManager.WRITE_ALIAS);
        try (OpenSearchConnection connection = new OpenSearchClientFactory().create(
                new OpenSearchProperties(endpoint, null, null))) {
            assertThat(new OpenSearchIndexManager(connection.client()).countNodes(OpenSearchIndexManager.READ_ALIAS))
                    .isGreaterThanOrEqualTo(50);
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
                Map.entry("rag.workers.enabled", "false"),
                Map.entry("rag.opensearch.endpoint", endpoint.toString()),
                Map.entry("rag.retrieval.vector.enabled", "false"),
                Map.entry("rag.bootstrap-admin.username", "retrieval-admin"),
                Map.entry("rag.bootstrap-admin.password", "retrieval-admin-password"),
                Map.entry("rag.bootstrap-admin.display-name", "Retrieval Admin"),
                Map.entry("spring.autoconfigure.exclude", String.join(",",
                        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
                        "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
                        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")),
                Map.entry("logging.level.root", "ERROR")));
        properties.putAll(overrides);
        return new SpringApplicationBuilder(RagApplication.class, CountingEmbeddingConfiguration.class)
                .profiles("local")
                .initializers(application -> application.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("retrieval-acceptance-test", properties)))
                .run();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "rag.test.counting-embedding", havingValue = "true", matchIfMissing = true)
    static class CountingEmbeddingConfiguration {
        @Bean
        @Primary
        EmbeddingPort countingEmbeddingPort() {
            return texts -> {
                EMBEDDING_CALLS.incrementAndGet();
                throw new EmbeddingUnavailableException("test embedding must not be called");
            };
        }
    }
}
