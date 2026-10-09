package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.access.role.RoleAssignmentService;
import com.yunjihai.rag.access.role.SpaceRole;
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
import com.yunjihai.rag.knowledge.DocumentRepository;
import com.yunjihai.rag.knowledge.DocumentStatus;
import com.yunjihai.rag.knowledge.KnowledgeSpaceService;
import com.yunjihai.rag.retrieval.TrustedRetrievalService;
import com.yunjihai.rag.retrieval.domain.DegradationReason;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.domain.RetrievalQuery;
import com.yunjihai.rag.retrieval.projection.ProjectionWorker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "rag.workers.enabled=false"
})
@ActiveProfiles("local")
class CoreKnowledgeManagementAcceptanceTest {
    private static final String ADMIN_USERNAME = "core-acceptance-admin";
    private static final String ADMIN_PASSWORD = "core-acceptance-password";
    private static final String INDEX = "chunks-core-knowledge-acceptance";
    private static final byte[] REPRESENTATIVE_MARKDOWN = ("# 制造业交付知识\n\n"
            + "Kubernetes 灰度发布必须包含回滚步骤和验收检查。\n")
            .getBytes(StandardCharsets.UTF_8);
    private static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            DockerImageName.parse("opensearchproject/opensearch:3.8.0"))
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));
    private static URI openSearchEndpoint;
    private static boolean infrastructureStarted;

    @Autowired
    private LocalAuthenticationService authentication;

    @Autowired
    private UserAdministrationService users;

    @Autowired
    private RoleAssignmentService roles;

    @Autowired
    private KnowledgeSpaceService spaces;

    @Autowired
    @Qualifier("runtimeUploadService")
    private UploadService uploads;

    @Autowired
    private IngestionJobService ingestion;

    @Autowired
    private DocumentService documents;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ProjectionWorker projections;

    @Autowired
    private OpenSearchIndexManager indexes;

    @Autowired
    private TrustedRetrievalService retrieval;

    @Autowired
    private JdbcClient jdbc;

    @BeforeAll
    static void startDependencies() {
        startInfrastructure();
    }

    @AfterAll
    static void stopDependencies() {
        if (OPENSEARCH.isRunning()) OPENSEARCH.stop();
        LocalFoundationInfrastructure.stop();
        infrastructureStarted = false;
    }

    @DynamicPropertySource
    static void localProperties(DynamicPropertyRegistry properties) {
        startInfrastructure();
        properties.add("spring.datasource.url", LocalFoundationInfrastructure::jdbcUrl);
        properties.add("spring.datasource.username", () -> LocalFoundationInfrastructure.RUNTIME_USER);
        properties.add("spring.datasource.password", () -> LocalFoundationInfrastructure.RUNTIME_PASSWORD);
        properties.add("spring.flyway.url", LocalFoundationInfrastructure::jdbcUrl);
        properties.add("spring.flyway.user", () -> LocalFoundationInfrastructure.MIGRATION_USER);
        properties.add("spring.flyway.password", () -> LocalFoundationInfrastructure.MIGRATION_PASSWORD);
        properties.add("rag.minio.endpoint", LocalFoundationInfrastructure::minioEndpoint);
        properties.add("rag.minio.access-key", () -> LocalFoundationInfrastructure.MINIO_APP_USER);
        properties.add("rag.minio.secret-key", () -> LocalFoundationInfrastructure.MINIO_APP_PASSWORD);
        properties.add("rag.minio.bucket", () -> LocalFoundationInfrastructure.BUCKET);
        properties.add("rag.opensearch.enabled", () -> "true");
        properties.add("rag.opensearch.endpoint", () -> openSearchEndpoint.toString());
        properties.add("rag.retrieval.vector.enabled", () -> "false");
        properties.add("rag.parser.endpoint", () -> "http://127.0.0.1:9");
        properties.add("rag.bootstrap-admin.username", () -> ADMIN_USERNAME);
        properties.add("rag.bootstrap-admin.password", () -> ADMIN_PASSWORD);
        properties.add("rag.bootstrap-admin.display-name", () -> "核心闭环验收管理员");
        properties.add("management.endpoint.health.group.readiness.include",
                () -> "readinessState,db,minio,opensearchRead,opensearchProjection");
    }

    @Test
    void completesGovernedKnowledgeFlowAcrossRealInfrastructure() {
        var admin = authentication.authenticate(
                ADMIN_USERNAME, ADMIN_PASSWORD.toCharArray(), "127.0.0.1");
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        var spaceAdmin = createUser("space-admin-" + suffix, "空间管理员", admin.userId());
        var editor = createUser("editor-" + suffix, "知识编辑者", admin.userId());
        var reviewer = createUser("reviewer-" + suffix, "审核发布者", admin.userId());
        var reader = createUser("reader-" + suffix, "只读成员", admin.userId());

        var now = Instant.now();
        var space = spaces.createSpace("核心闭环验收空间-" + suffix, "仅用于自动化验收", admin.userId(), now);
        roles.grantSpaceRole(admin.userId(), space.id(), spaceAdmin, SpaceRole.SPACE_ADMIN, now);
        roles.grantSpaceRole(admin.userId(), space.id(), editor, SpaceRole.EDITOR, now);
        roles.grantSpaceRole(admin.userId(), space.id(), reviewer, SpaceRole.REVIEWER_PUBLISHER, now);
        roles.grantSpaceRole(admin.userId(), space.id(), reader, SpaceRole.READER, now);

        assertThat(roles.activeSpaceRoles(spaceAdmin, space.id())).containsExactly(SpaceRole.SPACE_ADMIN);
        assertThat(roles.activeSpaceRoles(editor, space.id())).containsExactly(SpaceRole.EDITOR);
        assertThat(roles.activeSpaceRoles(reviewer, space.id()))
                .containsExactly(SpaceRole.REVIEWER_PUBLISHER);
        assertThat(roles.activeSpaceRoles(reader, space.id())).containsExactly(SpaceRole.READER);

        var version = uploads.accept(new UploadCommand(
                space.id(),
                "制造业 Kubernetes 灰度交付规范",
                "内部",
                List.of("制造业", "交付", "Kubernetes"),
                "delivery-guide.md",
                MediaType.parseMediaType("text/markdown"),
                REPRESENTATIVE_MARKDOWN.length,
                new ByteArrayInputStream(REPRESENTATIVE_MARKDOWN),
                editor), "core-acceptance-upload-" + suffix);

        var lease = ingestion.claimNext("core-acceptance-parser").orElseThrow();
        ingestion.complete(lease, new CanonicalDocumentV1(
                "canonical-document-v1",
                lease.jobId(),
                ParserStatus.SUCCEEDED,
                new CanonicalDocumentBody(
                        "delivery-guide.md",
                        "text/markdown",
                        1,
                        List.of(
                                new CanonicalNode(
                                        "heading-1", "HEADING", "制造业交付知识",
                                        null, null, null, null, 0.99),
                                new CanonicalNode(
                                        "paragraph-1", "PARAGRAPH",
                                        "Kubernetes 灰度发布必须包含回滚步骤和验收检查。",
                                        "heading-1", null, null, null, 0.98))),
                List.of()));

        assertThat(documentRepository.findById(version.documentId()).orElseThrow().latestVersion().status())
                .isEqualTo(DocumentStatus.DRAFT);
        documents.submitForReview(version.documentId(), version.versionId(), editor, now.plusSeconds(1));
        assertThatThrownBy(() -> documents.publish(
                version.documentId(), version.versionId(), editor, now.plusSeconds(2)))
                .isExactlyInstanceOf(AccessDeniedAsNotFoundException.class);
        documents.reject(
                version.documentId(), version.versionId(), reviewer,
                "请补充回滚验收说明", now.plusSeconds(3));
        documents.submitForReview(version.documentId(), version.versionId(), editor, now.plusSeconds(4));
        var published = documents.publish(
                version.documentId(), version.versionId(), reviewer, now.plusSeconds(5));
        assertThat(published.currentPublishedVersionId()).contains(version.versionId());

        int projected = projections.runOnce(
                "core-acceptance-projection", OpenSearchIndexManager.WRITE_ALIAS);
        assertThat(projected).isGreaterThan(0);
        indexes.refresh(OpenSearchIndexManager.READ_ALIAS);
        assertThat(indexes.countNodesForDocumentVersion(
                OpenSearchIndexManager.READ_ALIAS, version.documentId(), version.versionId()))
                .isEqualTo(2);

        var first = retrieval.retrieve(new RetrievalQuery(
                UUID.randomUUID(), reader, space.id(), "Kubernetes 灰度发布回滚", Map.of(), 8));
        assertThat(first.evidence()).isNotEmpty();
        assertThat(first.evidence().getFirst().documentId()).isEqualTo(version.documentId());
        assertThat(first.evidence().getFirst().versionId()).isEqualTo(version.versionId());
        assertThat(first.evidence().getFirst().locator()).isInstanceOf(MarkdownLocator.class);
        assertThat(first.degradationReasons()).contains(
                DegradationReason.VECTOR_UNAVAILABLE,
                DegradationReason.RERANKER_UNAVAILABLE);
        assertThat(documents.getAuthorizedSource(reader, version.documentId(), Instant.now()).bytes())
                .isEqualTo(REPRESENTATIVE_MARKDOWN);

        roles.revokeSpaceRole(
                admin.userId(), space.id(), reader, SpaceRole.READER, Instant.now());
        var afterRevocation = retrieval.retrieve(new RetrievalQuery(
                UUID.randomUUID(), reader, space.id(), "Kubernetes 灰度发布回滚", Map.of(), 8));
        assertThat(afterRevocation.stats().fusedCount()).isPositive();
        assertThat(afterRevocation.stats().reauthorizedCount()).isZero();
        assertThat(afterRevocation.evidence()).isEmpty();
        assertThatThrownBy(() -> documents.getAuthorizedSource(
                reader, version.documentId(), Instant.now()))
                .isExactlyInstanceOf(AccessDeniedAsNotFoundException.class);

        assertAuditIsCompleteAndSanitized();
    }

    private UUID createUser(String username, String displayName, UUID actorId) {
        return users.createUser(
                username, displayName, ("acceptance-" + username).toCharArray(), actorId).id();
    }

    private void assertAuditIsCompleteAndSanitized() {
        var actions = jdbc.sql("SELECT DISTINCT action FROM audit_event ORDER BY action")
                .query(String.class).list();
        assertThat(actions).contains(
                "USER_CREATE", "ROLE_CHANGE", "UPLOAD", "PARSE", "REVIEW",
                "PUBLISH", "RETRIEVE", "DOWNLOAD");
        String auditPayload = jdbc.sql("""
                SELECT COALESCE(string_agg(metadata::text, '' ORDER BY occurred_at), '')
                FROM audit_event
                """).query(String.class).single();
        assertThat(auditPayload)
                .doesNotContain(ADMIN_PASSWORD)
                .doesNotContain(LocalFoundationInfrastructure.MINIO_ROOT_PASSWORD)
                .doesNotContain(LocalFoundationInfrastructure.MINIO_APP_PASSWORD)
                .doesNotContain("Kubernetes 灰度发布必须包含回滚步骤")
                .doesNotContain("passwordHash", "sourceBlobId", "canonical_node", "acl_entry");
    }

    private static synchronized void startInfrastructure() {
        if (infrastructureStarted) return;
        LocalFoundationInfrastructure.start();
        OPENSEARCH.start();
        openSearchEndpoint = URI.create(
                "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
        try (var connection = new OpenSearchClientFactory().create(
                new OpenSearchProperties(openSearchEndpoint, null, null))) {
            var manager = new OpenSearchIndexManager(connection.client());
            manager.createGeneration(INDEX, OptionalInt.empty());
            manager.switchAliases(null, INDEX);
        } catch (java.io.IOException failure) {
            OPENSEARCH.stop();
            LocalFoundationInfrastructure.stop();
            throw new IllegalStateException("CORE_ACCEPTANCE_OPENSEARCH_SETUP_FAILED", failure);
        }
        infrastructureStarted = true;
    }
}
