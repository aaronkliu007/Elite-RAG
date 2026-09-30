package com.yunjihai.rag.infrastructure.minio;

import com.yunjihai.rag.ingestion.MinioBlobStore;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinioBlobStoreIntegrationTest {
    private static final String IMAGE = "quay.io/minio/aistor/minio@sha256:107cf2014a9583c74c11e3cdbd6903d89c2244355886b89ce532f4ecae9c23f3";
    private static final Path LICENSE = Path.of(java.util.Optional.ofNullable(System.getenv("RAG_MINIO_LICENSE_FILE"))
            .filter(value -> !value.isBlank())
            .orElse("E:\\git_secrets\\Elite-RAG\\minio.license"));
    private static final String ROOT_USER = "task7-root-user";
    private static final String ROOT_PASSWORD = "task7-root-password-only";
    private static final String BUCKET = "task7-documents";
    private static final String READ_ONLY_BUCKET = "task7-read-only";
    private static final String MISSING_BUCKET = "task7-missing";
    private static final byte[] PAYLOAD = "immutable-payload".getBytes(StandardCharsets.UTF_8);
    private static final String SHA256 = "4e83c66fe3bd986011cac25ea49c2f40551dfbd75264686137ec8c1d97f4b753";
    private static final String KEY = "sha256/4e/" + SHA256;

    private static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withEnv("MINIO_ROOT_USER", ROOT_USER)
            .withEnv("MINIO_ROOT_PASSWORD", ROOT_PASSWORD)
            .withFileSystemBind(LICENSE.toString(), "/minio.license", BindMode.READ_ONLY)
            .withCommand("server", "/data", "--console-address", ":9001", "--license", "/minio.license")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    private static MinioClient admin;
    private static MinioAsyncClient objectSdk;
    private static SdkMinioObjectClient objects;

    @BeforeAll
    static void startMinio() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isRegularFile(LICENSE), "仓库外 AIStor license 不存在，跳过官方 AIStor 协议测试");
        MINIO.start();
        admin = MinioClient.builder().endpoint(MINIO.getHost(), MINIO.getMappedPort(9000), false)
                .credentials(ROOT_USER, ROOT_PASSWORD).build();
        admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        admin.makeBucket(MakeBucketArgs.builder().bucket(READ_ONLY_BUCKET).build());
        admin.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(READ_ONLY_BUCKET)
                .config(readOnlyPolicy(READ_ONLY_BUCKET)).build());
        objectSdk = newAsyncClient();
        objects = new SdkMinioObjectClient(objectSdk);
    }

    @AfterAll
    static void stopMinio() throws Exception {
        if (admin != null) admin.close();
        if (objectSdk != null) objectSdk.close();
        if (MINIO.isRunning()) MINIO.stop();
    }

    @Test
    void firstWriteCreatesAndIdenticalReplayDoesNotOverwrite() throws Exception {
        var store = new MinioBlobStore(BUCKET, objects, ignored -> { });

        var first = store.putIfAbsent(SHA256, stream(PAYLOAD), PAYLOAD.length, MediaType.APPLICATION_PDF);
        var replay = store.putIfAbsent(SHA256, stream(PAYLOAD), PAYLOAD.length, MediaType.APPLICATION_PDF);

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(first.objectKey()).isEqualTo(KEY).doesNotContain("report.pdf");
        try (var stored = admin.getObject(GetObjectArgs.builder().bucket(BUCKET).object(KEY).build())) {
            assertThat(stored.readAllBytes()).isEqualTo(PAYLOAD);
        }
        var metadata = admin.statObject(StatObjectArgs.builder().bucket(BUCKET).object(KEY).build());
        assertThat(metadata.userMetadata().getFirst("sha256")).isEqualTo(SHA256);
        assertThat(metadata.contentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
    }

    @Test
    void twoIndependentWritersAtomicallyCreateExactlyOnce() throws Exception {
        byte[] firstBytes = new byte[8 * 1024 * 1024];
        byte[] secondBytes = new byte[8 * 1024 * 1024];
        java.util.Arrays.fill(firstBytes, (byte) 0x5a);
        java.util.Arrays.fill(secondBytes, (byte) 0x6b);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(firstBytes));
        String key = key(sha);
        var gate = new CyclicBarrier(2);
        var firstSdk = newAsyncClient();
        var secondSdk = newAsyncClient();
        var firstObjects = new SdkMinioObjectClient(firstSdk);
        var secondObjects = new SdkMinioObjectClient(secondSdk);
        try (firstSdk; secondSdk; var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return firstObjects.putIfAbsent(
                        BUCKET, key, stream(firstBytes), firstBytes.length, "application/octet-stream");
            });
            var second = executor.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return secondObjects.putIfAbsent(
                        BUCKET, key, stream(secondBytes), secondBytes.length, "application/octet-stream");
            });

            boolean firstCreated = first.get(30, TimeUnit.SECONDS);
            boolean secondCreated = second.get(30, TimeUnit.SECONDS);
            assertThat(List.of(firstCreated, secondCreated))
                    .containsExactlyInAnyOrder(true, false);
            try (var stored = admin.getObject(GetObjectArgs.builder().bucket(BUCKET).object(key).build())) {
                assertThat(stored.readAllBytes()).isEqualTo(firstCreated ? firstBytes : secondBytes);
            }
        }
    }

    @Test
    void existingObjectMustMatchLengthMediaTypeAndDigestMetadata() throws Exception {
        String lengthSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        assertThat(objects.putIfAbsent(BUCKET, key(lengthSha), stream(new byte[]{1}), 1, "application/pdf")).isTrue();
        assertConflict(() -> objects.putIfAbsent(
                BUCKET, key(lengthSha), stream(new byte[]{1, 2}), 2, "application/pdf"));

        String typeSha = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
        assertThat(objects.putIfAbsent(BUCKET, key(typeSha), stream(new byte[]{1}), 1, "application/pdf")).isTrue();
        assertConflict(() -> objects.putIfAbsent(BUCKET, key(typeSha), stream(new byte[]{1}), 1, "text/plain"));

        String missingSha = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
        admin.putObject(PutObjectArgs.builder().bucket(BUCKET).object(key(missingSha))
                .stream(stream(new byte[]{1}), 1L, -1L).contentType("application/pdf").build());
        assertConflict(() -> objects.putIfAbsent(BUCKET, key(missingSha), stream(new byte[]{1}), 1, "application/pdf"));

        String wrongSha = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
        admin.putObject(PutObjectArgs.builder().bucket(BUCKET).object(key(wrongSha))
                .stream(stream(new byte[]{1}), 1L, -1L).contentType("application/pdf")
                .userMetadata(java.util.Map.of("sha256", "f".repeat(64))).build());
        assertConflict(() -> objects.putIfAbsent(BUCKET, key(wrongSha), stream(new byte[]{1}), 1, "application/pdf"));
    }

    @Test
    void readinessUsesDedicatedRandomProbeAndReportsNormalMissingAndReadOnlyBuckets() throws Exception {
        String businessKey = "sha256/ff/" + "f".repeat(64);
        admin.putObject(PutObjectArgs.builder().bucket(BUCKET).object(businessKey)
                .stream(stream(new byte[]{9}), 1L, -1L).contentType("application/octet-stream").build());

        var healthy = new MinioReadinessHealthIndicator(admin, BUCKET).health();
        var missing = new MinioReadinessHealthIndicator(admin, MISSING_BUCKET).health();
        var anonymous = MinioClient.builder().endpoint(MINIO.getHost(), MINIO.getMappedPort(9000), false).build();
        var readOnly = new MinioReadinessHealthIndicator(anonymous, READ_ONLY_BUCKET).health();

        assertThat(healthy.getStatus()).isEqualTo(Status.UP);
        assertThat(missing.getStatus()).isEqualTo(Status.DOWN);
        assertThat(readOnly.getStatus()).isEqualTo(Status.DOWN);
        assertThat(missing.getDetails()).containsOnlyKeys("storage").doesNotContainValue(ROOT_PASSWORD);
        assertThat(readOnly.getDetails()).containsOnlyKeys("storage").doesNotContainValue(ROOT_PASSWORD);
        assertThat(admin.statObject(StatObjectArgs.builder().bucket(BUCKET).object(businessKey).build()).size())
                .isEqualTo(1);
        assertThat(admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET)
                        .prefix(MinioReadinessHealthIndicator.PROBE_PREFIX).recursive(true).build()))
                .isEmpty();
        anonymous.close();
    }

    @Test
    void clientPropertiesDoNotExposeSecretsOrEndpointQueryInFailures() {
        String secret = "not-for-errors";
        String endpoint = "http://127.0.0.1:1/storage?token=not-for-errors";

        assertThatThrownBy(() -> new MinioClientProperties(endpoint, "access", secret, BUCKET).client())
                .hasMessage("MINIO_CONFIGURATION_INVALID")
                .hasNoCause()
                .message().doesNotContain(secret).doesNotContain("token=");
    }

    @Test
    void clientPropertiesBindFromRagMinioNamespace() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(
                        "rag.minio.endpoint=http://127.0.0.1:9000",
                        "rag.minio.access-key=app-access",
                        "rag.minio.secret-key=app-secret",
                        "rag.minio.bucket=rag-documents")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MinioClientProperties.class))
                            .isEqualTo(new MinioClientProperties(
                                    "http://127.0.0.1:9000", "app-access", "app-secret", "rag-documents"));
                });
    }

    private static void assertConflict(ThrowingAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MINIO_OBJECT_CONFLICT")
                .hasNoCause()
                .message().doesNotContain(ROOT_PASSWORD).doesNotContain("If-None-Match");
    }

    private static ByteArrayInputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    private static MinioAsyncClient newAsyncClient() {
        return MinioAsyncClient.builder().endpoint(MINIO.getHost(), MINIO.getMappedPort(9000), false)
                .credentials(ROOT_USER, ROOT_PASSWORD).build();
    }

    private static String key(String sha256) {
        return "sha256/" + sha256.substring(0, 2) + "/" + sha256;
    }

    private static String readOnlyPolicy(String bucket) {
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"AWS":["*"]},
                   "Action":["s3:GetBucketLocation","s3:GetObject"],
                   "Resource":["arn:aws:s3:::%s","arn:aws:s3:::%s/*"]}
                ]}
                """.formatted(bucket, bucket);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MinioClientProperties.class)
    static class PropertiesConfiguration {
    }
}
