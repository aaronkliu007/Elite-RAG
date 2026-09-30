package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.access.AccessDecisionRepository;
import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.access.AclService;
import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.access.PrincipalType;
import com.yunjihai.rag.audit.AuditEventStore;
import com.yunjihai.rag.identity.ExternalDepartment;
import com.yunjihai.rag.identity.ExternalUser;
import com.yunjihai.rag.identity.OrganizationReconciliationPort;
import com.yunjihai.rag.identity.OrganizationSnapshot;
import com.yunjihai.rag.identity.OrganizationSyncService;
import com.yunjihai.rag.infrastructure.minio.SdkMinioObjectClient;
import com.yunjihai.rag.infrastructure.postgres.JdbcAccessDecisionRepository;
import com.yunjihai.rag.infrastructure.postgres.JdbcAuditEventStore;
import com.yunjihai.rag.infrastructure.postgres.JdbcBlobCleanupEligibilityRepository;
import com.yunjihai.rag.infrastructure.postgres.JdbcDocumentRepository;
import com.yunjihai.rag.infrastructure.postgres.JdbcIngestionJobRepository;
import com.yunjihai.rag.infrastructure.postgres.JdbcKnowledgeSpaceRepository;
import com.yunjihai.rag.infrastructure.postgres.JdbcOrganizationReconciliationAdapter;
import com.yunjihai.rag.infrastructure.postgres.JdbcOrphanBlobRecorder;
import com.yunjihai.rag.infrastructure.postgres.JdbcParsedDocumentStore;
import com.yunjihai.rag.infrastructure.postgres.JdbcPrincipalMembershipRepository;
import com.yunjihai.rag.infrastructure.postgres.JdbcSessionVersionStore;
import com.yunjihai.rag.ingestion.BlobCleanupEligibilityRepository;
import com.yunjihai.rag.ingestion.BlobStore;
import com.yunjihai.rag.ingestion.CanonicalDocumentBody;
import com.yunjihai.rag.ingestion.CanonicalDocumentV1;
import com.yunjihai.rag.ingestion.CanonicalNode;
import com.yunjihai.rag.ingestion.DocumentVersionId;
import com.yunjihai.rag.ingestion.IngestionJobRepository;
import com.yunjihai.rag.ingestion.IngestionJobService;
import com.yunjihai.rag.ingestion.IngestionRequest;
import com.yunjihai.rag.ingestion.OrphanBlobRecorder;
import com.yunjihai.rag.ingestion.ParserStatus;
import com.yunjihai.rag.ingestion.ParsedDocumentStore;
import com.yunjihai.rag.knowledge.DocumentService;
import com.yunjihai.rag.knowledge.DocumentStatus;
import com.yunjihai.rag.knowledge.DocumentRepository;
import com.yunjihai.rag.knowledge.KnowledgeSpaceService;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import com.yunjihai.rag.knowledge.KnowledgeSpaceRepository;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.main.web-application-type=none")
@ActiveProfiles("local")
@Import(PostgresFoundationAcceptanceTest.AcceptanceAuthorization.class)
class PostgresFoundationAcceptanceTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private OrganizationSyncService organizations;

    @Autowired
    private KnowledgeSpaceService spaces;

    @Autowired
    private AclService acl;

    @Autowired
    private BlobStore blobs;

    @Autowired
    private DocumentService documents;

    @Autowired
    private IngestionJobService ingestion;

    @Autowired
    private MinioClient minio;

    @BeforeAll
    static void requireOfficialInfrastructure() {
        LocalFoundationInfrastructure.start();
    }

    @AfterAll
    static void stopOfficialInfrastructure() {
        LocalFoundationInfrastructure.stop();
    }

    @DynamicPropertySource
    static void localProperties(DynamicPropertyRegistry properties) {
        LocalFoundationInfrastructure.start();
        LocalFoundationInfrastructure.register(properties);
    }

    @BeforeEach
    void clearDatabase() throws Exception {
        try (var connection = DriverManager.getConnection(
                LocalFoundationInfrastructure.jdbcUrl(),
                LocalFoundationInfrastructure.MIGRATION_USER,
                LocalFoundationInfrastructure.MIGRATION_PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE app_user CASCADE");
        }
    }

    @Test
    void localProfileWiresEveryProductionFoundationAdapter() {
        assertThat(context.getBean(OrganizationReconciliationPort.class))
                .isInstanceOf(JdbcOrganizationReconciliationAdapter.class);
        assertThat(context.getBean(JdbcSessionVersionStore.class)).isNotNull();
        assertThat(context.getBean(KnowledgeSpaceRepository.class))
                .isInstanceOf(JdbcKnowledgeSpaceRepository.class);
        assertThat(context.getBean(DocumentRepository.class)).isInstanceOf(JdbcDocumentRepository.class);
        assertThat(context.getBean(AuditEventStore.class)).isInstanceOf(JdbcAuditEventStore.class);
        assertThat(context.getBean(JdbcPrincipalMembershipRepository.class)).isNotNull();
        assertThat(context.getBean(AccessDecisionRepository.class))
                .isInstanceOf(JdbcAccessDecisionRepository.class);
        assertThat(context.getBean(IngestionJobRepository.class))
                .isInstanceOf(JdbcIngestionJobRepository.class);
        assertThat(context.getBean(ParsedDocumentStore.class)).isInstanceOf(JdbcParsedDocumentStore.class);
        assertThat(context.getBean(OrphanBlobRecorder.class)).isInstanceOf(JdbcOrphanBlobRecorder.class);
        assertThat(context.getBean(BlobCleanupEligibilityRepository.class))
                .isInstanceOf(JdbcBlobCleanupEligibilityRepository.class);
        assertThat(context.getBean(SdkMinioObjectClient.class)).isNotNull();
        assertThat(context.getBean(BlobStore.class)).isNotNull();
    }

    @Test
    void authorizedEmptyKnowledgeSpaceReturnsEmptyList() {
        Instant at = Instant.now();
        synchronizeSpaceReaders(at);
        UUID owner = userId("owner");
        UUID reader = userId("reader");
        var space = spaces.createSpace("空知识库", owner, at);
        acl.grantSpace(owner, space.id(), new PrincipalRef(PrincipalType.USER, reader), null, at);

        assertThat(scalar("SELECT count(*) FROM document")).isZero();
        assertThat(documents.listAuthorizedDocuments(reader, space.id(), at)).isEmpty();
    }

    @Test
    void unauthorizedOrMissingKnowledgeSpacesAreHidden() {
        Instant at = Instant.now();
        synchronizeSpaceReaders(at);
        UUID owner = userId("owner");
        UUID reader = userId("reader");
        var space = spaces.createSpace("未授权知识库", owner, at);

        assertThatThrownBy(() -> documents.listAuthorizedDocuments(reader, space.id(), at))
                .isInstanceOf(AccessDeniedAsNotFoundException.class).hasNoCause();
        assertThatThrownBy(() -> documents.listAuthorizedDocuments(reader, UUID.randomUUID(), at))
                .isInstanceOf(AccessDeniedAsNotFoundException.class).hasNoCause();
    }

    @Test
    void emptyKnowledgeSpaceRejectsExpiredAndRevokedSpaceAcl() {
        Instant at = Instant.now();
        synchronizeSpaceReaders(at);
        UUID owner = userId("owner");
        UUID reader = userId("reader");
        var space = spaces.createSpace("限时知识库", owner, at);
        var principal = new PrincipalRef(PrincipalType.USER, reader);
        acl.grantSpace(owner, space.id(), principal, at.plusSeconds(2), at);

        assertThat(documents.listAuthorizedDocuments(reader, space.id(), at.plusSeconds(1))).isEmpty();
        assertThatThrownBy(() -> documents.listAuthorizedDocuments(reader, space.id(), at.plusSeconds(2)))
                .isInstanceOf(AccessDeniedAsNotFoundException.class).hasNoCause();
        acl.grantSpace(owner, space.id(), principal, null, at.plusSeconds(3));
        assertThat(documents.listAuthorizedDocuments(reader, space.id(), at.plusSeconds(3))).isEmpty();
        acl.revokeSpace(owner, space.id(), principal, at.plusSeconds(4));
        assertThatThrownBy(() -> documents.listAuthorizedDocuments(reader, space.id(), at.plusSeconds(4)))
                .isInstanceOf(AccessDeniedAsNotFoundException.class).hasNoCause();
    }

    @Test
    void emptyKnowledgeSpaceUsesCurrentEnabledUserAndDepartmentPrincipals() {
        Instant at = Instant.now();
        synchronizeSpaceReaders(at);
        UUID owner = userId("owner");
        UUID reader = userId("reader");
        UUID department = jdbc.sql("SELECT id FROM org_unit WHERE external_id = 'root'")
                .query(UUID.class).single();
        var space = spaces.createSpace("组织知识库", owner, at);
        acl.grantSpace(owner, space.id(), new PrincipalRef(PrincipalType.DEPARTMENT, department), null, at);

        assertThat(documents.listAuthorizedDocuments(reader, space.id(), at)).isEmpty();
        jdbc.sql("UPDATE org_unit SET enabled = false WHERE id = :id").param("id", department).update();
        assertThatThrownBy(() -> documents.listAuthorizedDocuments(reader, space.id(), at))
                .isInstanceOf(AccessDeniedAsNotFoundException.class).hasNoCause();
        jdbc.sql("UPDATE org_unit SET enabled = true WHERE id = :id").param("id", department).update();
        jdbc.sql("DELETE FROM org_membership WHERE user_id = :id").param("id", reader).update();
        assertThatThrownBy(() -> documents.listAuthorizedDocuments(reader, space.id(), at))
                .isInstanceOf(AccessDeniedAsNotFoundException.class).hasNoCause();

        acl.grantSpace(owner, space.id(), new PrincipalRef(PrincipalType.USER, reader), null, at);
        assertThat(documents.listAuthorizedDocuments(reader, space.id(), at)).isEmpty();
        jdbc.sql("UPDATE app_user SET enabled = false WHERE id = :id").param("id", reader).update();
        assertThatThrownBy(() -> documents.listAuthorizedDocuments(reader, space.id(), at))
                .isInstanceOf(AccessDeniedAsNotFoundException.class).hasNoCause();
    }

    @Test
    void persistsTheCompleteKnowledgeFoundationFlowAcrossPostgresAndAistor() throws Exception {
        Instant at = Instant.now().minusSeconds(5);
        organizations.synchronize(new OrganizationSnapshot(
                List.of(new ExternalDepartment("engineering", null, "研发中心")),
                List.of(
                        new ExternalUser("owner", "Owner", true, Set.of("engineering")),
                        new ExternalUser("reader", "Reader", true, Set.of("engineering")),
                        new ExternalUser("outsider", "Outsider", true, Set.of("engineering"))),
                at,
                true));
        UUID owner = userId("owner");
        UUID reader = userId("reader");
        UUID outsider = userId("outsider");

        var space = spaces.createSpace("研发知识库", owner, at.plusSeconds(1));
        acl.grantSpace(owner, space.id(), new PrincipalRef(PrincipalType.USER, reader),
                null, at.plusSeconds(2));

        byte[] source = "# Spring 装配验收\n\n真实对象与数据库。\n".getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        var stored = blobs.putIfAbsent(sha256, new ByteArrayInputStream(source), source.length,
                MediaType.TEXT_MARKDOWN);
        var draft = documents.createDraft(
                space.id(), "Spring 装配验收", stored.objectKey(), owner, at.plusSeconds(3));
        var version = new DocumentVersionId(draft.id(), draft.latestVersion().id());

        UUID jobId = ingestion.enqueue(new IngestionRequest(
                version, owner, stored.objectKey(), "foundation.md",
                MediaType.TEXT_MARKDOWN_VALUE, source.length, sha256));
        var lease = ingestion.claimNext("acceptance-worker").orElseThrow();
        assertThat(lease.jobId()).isEqualTo(jobId);
        var body = new CanonicalDocumentBody(
                "foundation.md",
                MediaType.TEXT_MARKDOWN_VALUE,
                1,
                List.of(new CanonicalNode(
                        "node-1", "PARAGRAPH", "真实对象与数据库。", null, 1,
                        null, List.of(), 0.99)));
        ingestion.complete(lease, new CanonicalDocumentV1(
                "canonical-document-v1", jobId, ParserStatus.SUCCEEDED, body, List.of()));

        var persisted = context.getBean(DocumentRepository.class).findById(draft.id()).orElseThrow();
        assertThat(persisted.latestVersion().status()).isEqualTo(DocumentStatus.IN_REVIEW);
        assertThat(documents.listAuthorizedDocuments(reader, space.id(), Instant.now())).isEmpty();
        var published = documents.publish(
                draft.id(), draft.latestVersion().id(), owner, Instant.now());
        assertThat(published.latestVersion().status()).isEqualTo(DocumentStatus.PUBLISHED);

        var authorized = documents.getAuthorizedDocument(reader, draft.id(), Instant.now());
        assertThat(authorized.id()).isEqualTo(draft.id());
        assertThatThrownBy(() -> documents.getAuthorizedDocument(outsider, draft.id(), Instant.now()))
                .isInstanceOf(AccessDeniedAsNotFoundException.class)
                .hasNoCause();

        assertThat(scalar("SELECT count(*) FROM app_user")).isEqualTo(3L);
        assertThat(scalar("SELECT count(*) FROM org_membership")).isEqualTo(3L);
        assertThat(scalar("SELECT count(*) FROM acl_entry WHERE revoked_at IS NULL")).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT status FROM ingestion_job WHERE id = :id")
                .param("id", jobId).query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("SELECT status || '/' || parse_status FROM document_version WHERE id = :id")
                .param("id", draft.latestVersion().id()).query(String.class).single())
                .isEqualTo("PUBLISHED/SUCCEEDED");
        assertThat(scalar("SELECT count(*) FROM canonical_document")).isEqualTo(1L);
        assertThat(scalar("SELECT count(*) FROM canonical_node")).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT body_text FROM canonical_node WHERE version_id = :id")
                .param("id", draft.latestVersion().id()).query(String.class).single())
                .isEqualTo("真实对象与数据库。");
        assertThat(jdbc.sql("SELECT action FROM audit_event ORDER BY occurred_at")
                .query(String.class).list())
                .contains("ACL_CHANGE", "PARSE", "PUBLISH", "VIEW")
                .doesNotContain("DOWNLOAD");

        var object = minio.statObject(StatObjectArgs.builder()
                .bucket(LocalFoundationInfrastructure.BUCKET).object(stored.objectKey()).build());
        assertThat(object.size()).isEqualTo(source.length);
        assertThat(object.contentType()).isEqualTo(MediaType.TEXT_MARKDOWN_VALUE);
        assertThat(object.userMetadata().getFirst("sha256")).isEqualTo(sha256);
        try (var input = minio.getObject(GetObjectArgs.builder()
                .bucket(LocalFoundationInfrastructure.BUCKET).object(stored.objectKey()).build())) {
            assertThat(input.readAllBytes()).isEqualTo(source);
        }
        assertThatThrownBy(() -> minio.removeObject(RemoveObjectArgs.builder()
                .bucket(LocalFoundationInfrastructure.BUCKET).object(stored.objectKey()).build()))
                .as("应用账号不得物理删除业务对象")
                .isInstanceOf(Exception.class);
        assertThat(minio.statObject(StatObjectArgs.builder()
                .bucket(LocalFoundationInfrastructure.BUCKET).object(stored.objectKey()).build()).size())
                .isEqualTo(source.length);

        var readerPrincipal = new PrincipalRef(PrincipalType.USER, reader);
        acl.grantDocument(owner, space.id(), draft.id(), readerPrincipal, null, Instant.now());
        acl.revokeDocument(owner, space.id(), draft.id(), readerPrincipal, Instant.now());
        assertThat(documents.listAuthorizedDocuments(reader, space.id(), Instant.now())).isEmpty();
        acl.grantDocument(owner, space.id(), draft.id(), readerPrincipal, null, Instant.now());
        assertThat(documents.listAuthorizedDocuments(reader, space.id(), Instant.now()))
                .extracting(document -> document.id()).containsExactly(draft.id());
        documents.archive(draft.id(), owner, Instant.now());
        assertThat(documents.listAuthorizedDocuments(reader, space.id(), Instant.now())).isEmpty();
    }

    private void synchronizeSpaceReaders(Instant at) {
        organizations.synchronize(new OrganizationSnapshot(
                List.of(new ExternalDepartment("root", null, "公司"),
                        new ExternalDepartment("engineering", "root", "研发中心")),
                List.of(new ExternalUser("owner", "Owner", true, Set.of("engineering")),
                        new ExternalUser("reader", "Reader", true, Set.of("engineering"))),
                at, true));
    }

    private UUID userId(String externalId) {
        return jdbc.sql("SELECT id FROM app_user WHERE external_id = :externalId")
                .param("externalId", externalId).query(UUID.class).single();
    }

    private long scalar(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AcceptanceAuthorization {
        @Bean
        @Primary
        KnowledgeWriteAuthorization acceptanceWriteAuthorization() {
            return KnowledgeWriteAuthorization.allowAll();
        }
    }
}

final class LocalFoundationInfrastructure {
    static final String AISTOR_IMAGE = "quay.io/minio/aistor/minio@sha256:107cf2014a9583c74c11e3cdbd6903d89c2244355886b89ce532f4ecae9c23f3";
    static final Path LICENSE = Path.of("E:\\git_secrets\\Elite-RAG\\minio.license");
    static final String DATABASE = "rag_acceptance";
    static final String MIGRATION_USER = "migration_owner";
    static final String MIGRATION_PASSWORD = "migration_test_only";
    static final String RUNTIME_USER = "rag_runtime_acceptance";
    static final String RUNTIME_PASSWORD = "runtime_test_only";
    static final String MINIO_ROOT_USER = "acceptance-root";
    static final String MINIO_ROOT_PASSWORD = "acceptance-root-password";
    static final String MINIO_APP_USER = "acceptance-app";
    static final String MINIO_APP_PASSWORD = "acceptance-app-password";
    static final String BUCKET = "rag-documents";
    private static final Path APP_POLICY = resolveAppPolicy();

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName(DATABASE).withUsername(MIGRATION_USER).withPassword(MIGRATION_PASSWORD);
    private static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(AISTOR_IMAGE))
            .withEnv("MINIO_ROOT_USER", MINIO_ROOT_USER)
            .withEnv("MINIO_ROOT_PASSWORD", MINIO_ROOT_PASSWORD)
            .withFileSystemBind(LICENSE.toString(), "/minio.license", BindMode.READ_ONLY)
            .withFileSystemBind(APP_POLICY.toString(), "/rag-documents-app-policy.json", BindMode.READ_ONLY)
            .withCommand("server", "/data", "--console-address", ":9001", "--license", "/minio.license")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000)
                    .withStartupTimeout(Duration.ofMinutes(2)));
    private static boolean started;

    private LocalFoundationInfrastructure() {
    }

    private static Path resolveAppPolicy() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (directory != null) {
            Path candidate = directory.resolve(
                    "infra/compose/minio-init/rag-documents-app-policy.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        return Path.of("infra/compose/minio-init/rag-documents-app-policy.json")
                .toAbsolutePath().normalize();
    }

    static synchronized void start() {
        if (started) return;
        if (!Files.isRegularFile(LICENSE)) {
            throw new IllegalStateException("AISTOR_LICENSE_MISSING");
        }
        if (!Files.isRegularFile(APP_POLICY)) {
            throw new IllegalStateException("AISTOR_APP_POLICY_MISSING");
        }
        POSTGRES.start();
        MINIO.start();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + RUNTIME_USER + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'");
            statement.execute("ALTER DATABASE " + DATABASE + " SET rag.runtime_role = '" + RUNTIME_USER + "'");
        } catch (Exception failure) {
            stop();
            throw new IllegalStateException("POSTGRES_ACCEPTANCE_SETUP_FAILED", failure);
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), MIGRATION_USER, MIGRATION_PASSWORD)
                .locations("classpath:db/migration").load().migrate();
        try (var admin = minioAdmin()) {
            admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
            configureApplicationIdentity();
        } catch (Exception failure) {
            stop();
            throw new IllegalStateException("MINIO_ACCEPTANCE_SETUP_FAILED", failure);
        }
        started = true;
    }

    static synchronized void stop() {
        if (MINIO.isRunning()) MINIO.stop();
        if (POSTGRES.isRunning()) POSTGRES.stop();
        started = false;
    }

    static void register(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", () -> RUNTIME_USER);
        properties.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        properties.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        properties.add("spring.flyway.user", () -> MIGRATION_USER);
        properties.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        properties.add("rag.minio.endpoint", LocalFoundationInfrastructure::minioEndpoint);
        properties.add("rag.minio.access-key", () -> MINIO_APP_USER);
        properties.add("rag.minio.secret-key", () -> MINIO_APP_PASSWORD);
        properties.add("rag.minio.bucket", () -> BUCKET);
    }

    static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    static MinioClient minioAdmin() {
        return MinioClient.builder().endpoint(minioEndpoint())
                .credentials(MINIO_ROOT_USER, MINIO_ROOT_PASSWORD).build();
    }

    private static void configureApplicationIdentity() throws Exception {
        execMc("alias", "set", "bootstrap", "http://127.0.0.1:9000",
                MINIO_ROOT_USER, MINIO_ROOT_PASSWORD);
        execMc("admin", "user", "add", "bootstrap", MINIO_APP_USER, MINIO_APP_PASSWORD);
        execMc("admin", "policy", "create", "bootstrap", "rag-documents-app-rw",
                "/rag-documents-app-policy.json");
        execMc("admin", "policy", "attach", "bootstrap", "rag-documents-app-rw",
                "--user", MINIO_APP_USER);
    }

    private static void execMc(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = "mc";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        var result = MINIO.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("AISTOR_APP_IDENTITY_SETUP_FAILED");
        }
    }
}
