package com.yunjihai.rag.ingestion;

import com.yunjihai.rag.infrastructure.minio.MinioBlobReader;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinioParserInvocationTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final UUID LEASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");
    private static final byte[] SOURCE = "# 受控解析".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temporaryDirectory;

    @Test
    void downloadsToControlledTemporaryFileAndAlwaysDeletesIt() throws Exception {
        var parsedPath = new AtomicReference<Path>();
        var parser = parser((job, sourceFile) -> {
            parsedPath.set(sourceFile);
            assertThat(sourceFile.getParent()).isEqualTo(temporaryDirectory.toAbsolutePath().normalize());
            try {
                assertThat(Files.readAllBytes(sourceFile)).isEqualTo(SOURCE);
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
            return successResponse(job.jobId());
        });
        var invocation = new MinioParserInvocation((objectKey, maxBytes) -> {
            assertThat(objectKey).isEqualTo(objectKey());
            assertThat(maxBytes).isEqualTo(100L * 1024 * 1024);
            return SOURCE;
        }, parser, temporaryDirectory, 60);

        var result = invocation.parse(lease());

        assertThat(result.status()).isEqualTo(ParserStatus.SUCCEEDED);
        assertThat(parsedPath.get()).isNotNull();
        assertThat(parsedPath.get()).doesNotExist();
    }

    @Test
    void rejectsLengthMismatchBeforeCallingParserAndDeletesTemporaryFile() throws Exception {
        var parserCalled = new java.util.concurrent.atomic.AtomicBoolean();
        var parser = parser((job, sourceFile) -> {
            parserCalled.set(true);
            return successResponse(job.jobId());
        });
        var invocation = new MinioParserInvocation(
                (objectKey, maxBytes) -> "short".getBytes(StandardCharsets.UTF_8),
                parser,
                temporaryDirectory,
                60);

        assertThatThrownBy(() -> invocation.parse(lease()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MINIO_OBJECT_LENGTH_MISMATCH")
                .hasNoCause();
        assertThat(parserCalled).isFalse();
        try (var paths = Files.list(temporaryDirectory)) {
            assertThat(paths).isEmpty();
        }
    }

    @Test
    void rejectsNonCanonicalObjectKeyBeforeOpeningMinioConnection() {
        var reader = blobReaderWithoutReachableEndpoint();

        assertThatThrownBy(() -> reader.read("documents/source.md", 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MINIO_OBJECT_KEY_INVALID")
                .hasNoCause();
    }

    @Test
    void rejectsReadLimitAboveGlobalUploadMaximumBeforeOpeningMinioConnection() {
        var reader = blobReaderWithoutReachableEndpoint();

        assertThatThrownBy(() -> reader.read(objectKey(), 100L * 1024 * 1024 + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MINIO_READ_LIMIT_INVALID")
                .hasNoCause();
    }

    private static MinioBlobReader blobReaderWithoutReachableEndpoint() {
        return new MinioBlobReader(
                MinioClient.builder()
                        .endpoint("http://127.0.0.1:1")
                        .credentials("test-access-key", "test-secret-key")
                        .build(),
                "documents");
    }

    private static ParserClient parser(ParserTransport transport) {
        var root = Path.of("..", "..").toAbsolutePath().normalize();
        return new ParserClient(
                transport,
                ParserContractValidator.from(root.resolve("contracts/parser/canonical-document-v1.schema.json")));
    }

    private static IngestionLease lease() {
        var request = new IngestionRequest(
                new DocumentVersionId(UUID.randomUUID(), UUID.randomUUID()),
                UUID.randomUUID(),
                objectKey(),
                "source.md",
                "text/markdown",
                SOURCE.length,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        return new IngestionLease(
                JOB_ID,
                LEASE_ID,
                1,
                "worker-1",
                Instant.parse("2026-10-08T10:05:00Z"),
                request);
    }

    private static String objectKey() {
        return "sha256/aa/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    }

    private static String successResponse(UUID jobId) {
        return """
                {"schemaVersion":"canonical-document-v1","jobId":"%s","status":"SUCCEEDED",
                 "document":{"sourceFilename":"source.md","mediaType":"text/markdown","pageCount":1,
                 "nodes":[{"nodeId":"n1","kind":"HEADING","text":"受控解析","parentNodeId":null,
                 "page":1,"bbox":null,"tableCells":[],"confidence":1.0}]},"errors":[]}
                """.formatted(jobId);
    }
}
