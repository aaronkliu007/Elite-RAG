package com.yunjihai.rag.infrastructure.minio;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.yunjihai.rag.ingestion.UploadPolicy;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SdkMinioObjectClientTest {
    private static final String BUCKET = "rag-documents";
    private static final String SHA = "a".repeat(64);
    private static final String KEY = "sha256/aa/" + SHA;
    private WireMockServer server;
    private MinioClient sync;
    private MinioAsyncClient async;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(0);
        server.start();
        String endpoint = "http://127.0.0.1:" + server.port();
        sync = MinioClient.builder().endpoint(endpoint).credentials("test-access", "test-secret").build();
        async = MinioAsyncClient.builder().endpoint(endpoint).credentials("test-access", "test-secret").build();
        server.stubFor(get(urlPathEqualTo("/" + BUCKET))
                .withQueryParam("location", equalTo(""))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml")
                        .withBody("<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                                + "us-east-1</LocationConstraint>")));
    }

    @AfterEach
    void stopServer() throws Exception {
        async.close();
        sync.close();
        server.stop();
    }

    @Test
    void completeMultipartRequestCarriesFinalCreateCondition() {
        stubSuccessfulMultipart();
        var transport = new PinnedMinioMultipartClient(async);

        assertThatCode(() -> {
            String uploadId = transport.begin(BUCKET, KEY, "application/pdf", SHA);
            var part = transport.uploadPart(BUCKET, KEY, uploadId, 1, new byte[]{1}, 1);
            transport.completeIfAbsent(BUCKET, KEY, uploadId, List.of(part));
        }).doesNotThrowAnyException();

        server.verify(1, postRequestedFor(urlPathEqualTo("/" + BUCKET + "/" + KEY))
                .withQueryParam("uploadId", equalTo("upload-1"))
                .withHeader("If-None-Match", equalTo("*")));
    }

    @Test
    void applicationLimitIsReadAndUploadedInBoundedParts() {
        var operations = new RecordingOperations();
        var objects = new SdkMinioObjectClient(operations);
        long length = UploadPolicy.defaults().maxBytes();

        assertThat(objects.putIfAbsent(
                BUCKET, KEY, new GeneratingInputStream(length), length, "application/pdf")).isTrue();

        assertThat(operations.partLengths).hasSize(20).allMatch(size -> size <= 5 * 1024 * 1024);
        assertThat(operations.partLengths).allMatch(size -> size == SdkMinioObjectClient.PART_SIZE_BYTES);
        assertThat(operations.completed).isTrue();
    }

    @Test
    void rejectsBeyondApplicationLimitBeforeMultipartStarts() {
        var operations = new RecordingOperations();
        var objects = new SdkMinioObjectClient(operations);

        assertThatThrownBy(() -> objects.putIfAbsent(
                BUCKET, KEY, InputStream.nullInputStream(), UploadPolicy.defaults().maxBytes() + 1,
                "application/pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MINIO_OBJECT_TOO_LARGE")
                .hasNoCause();
        assertThat(operations.begun).isFalse();
    }

    @Test
    void shortAndLongStreamsFailBeforeCompleteAndAbortUpload() {
        for (InputStream stream : List.of(
                new ByteArrayInputStream(new byte[]{1}),
                new ByteArrayInputStream(new byte[]{1, 2, 3}))) {
            var operations = new RecordingOperations();
            var objects = new SdkMinioObjectClient(operations);

            assertThatThrownBy(() -> objects.putIfAbsent(BUCKET, KEY, stream, 2, "application/pdf"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("MINIO_OBJECT_WRITE_FAILED")
                    .hasNoCause();
            assertThat(operations.completed).isFalse();
            assertThat(operations.aborted).isTrue();
        }
    }

    @Test
    void partFailureAbortsAndCannotExposeSdkDetails() {
        var operations = new RecordingOperations();
        operations.partFailure = new IOException("Authorization: credential-secret");
        var objects = new SdkMinioObjectClient(operations);

        assertThatThrownBy(() -> objects.putIfAbsent(
                BUCKET, KEY, new ByteArrayInputStream(new byte[]{1}), 1, "application/pdf"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MINIO_OBJECT_WRITE_FAILED")
                .hasNoCause()
                .message().doesNotContain("Authorization").doesNotContain("credential-secret");
        assertThat(operations.aborted).isTrue();
    }

    @Test
    void onlyExactCompleteConflictCanReconcileToIdempotentReplay() {
        var operations = matchingExistingObject();
        operations.completeFailure = s3Failure(412, "PreconditionFailed");
        var objects = new SdkMinioObjectClient(operations);

        assertThat(objects.putIfAbsent(
                BUCKET, KEY, new ByteArrayInputStream(new byte[]{1}), 1, "application/pdf")).isFalse();
        assertThat(operations.aborted).isTrue();
        assertThat(operations.statCalls).isEqualTo(1);
    }

    @Test
    void reconciliationStatFailureCannotExposeStorageDetails() {
        var operations = matchingExistingObject();
        operations.completeFailure = s3Failure(412, "PreconditionFailed");
        operations.statFailure = new IllegalStateException(
                "https://storage.internal.example Authorization: credential-secret");
        var objects = new SdkMinioObjectClient(operations);

        assertThatThrownBy(() -> objects.putIfAbsent(
                BUCKET, KEY, new ByteArrayInputStream(new byte[]{1}), 1, "application/pdf"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MINIO_OBJECT_WRITE_FAILED")
                .hasNoCause()
                .satisfies(error -> {
                    assertThat(error.getSuppressed()).isEmpty();
                    assertThat(error.getMessage())
                            .doesNotContain("storage.internal.example")
                            .doesNotContain("Authorization")
                            .doesNotContain("credential-secret");
                });
        assertThat(operations.aborted).isTrue();
        assertThat(operations.statCalls).isEqualTo(1);
    }

    @Test
    void completeHttpSuccessWithS3ErrorBodyAbortsAndReportsSafeFailure() {
        stubMultipartStartAndPart();
        server.stubFor(post(urlPathEqualTo("/" + BUCKET + "/" + KEY))
                .withQueryParam("uploadId", equalTo("upload-1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml")
                        .withBody("<Error><Code>InternalError</Code>"
                                + "<Message>Authorization: credential-secret at storage.internal.example</Message>"
                                + "<RequestId>request</RequestId><HostId>host</HostId></Error>")));
        server.stubFor(delete(urlPathEqualTo("/" + BUCKET + "/" + KEY))
                .withQueryParam("uploadId", equalTo("upload-1"))
                .willReturn(aResponse().withStatus(204)));
        var objects = new SdkMinioObjectClient(async);

        assertThatThrownBy(() -> objects.putIfAbsent(
                BUCKET, KEY, new ByteArrayInputStream(new byte[]{1}), 1, "application/pdf"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MINIO_OBJECT_WRITE_FAILED")
                .hasNoCause()
                .satisfies(error -> {
                    assertThat(error.getSuppressed()).isEmpty();
                    assertThat(error.getMessage())
                            .doesNotContain("storage.internal.example")
                            .doesNotContain("Authorization")
                            .doesNotContain("credential-secret");
                });
        server.verify(1, deleteRequestedFor(urlPathEqualTo("/" + BUCKET + "/" + KEY))
                .withQueryParam("uploadId", equalTo("upload-1")));
    }

    @Test
    void mismatchedStatusAndPermissionFailureNeverReconcile() {
        for (ErrorResponseException failure : List.of(
                s3Failure(500, "PreconditionFailed"), s3Failure(403, "AccessDenied"))) {
            var operations = matchingExistingObject();
            operations.completeFailure = failure;
            var objects = new SdkMinioObjectClient(operations);

            assertThatThrownBy(() -> objects.putIfAbsent(
                    BUCKET, KEY, new ByteArrayInputStream(new byte[]{1}), 1, "application/pdf"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("MINIO_OBJECT_WRITE_FAILED")
                    .hasNoCause();
            assertThat(operations.statCalls).isZero();
            assertThat(operations.aborted).isTrue();
        }
    }

    @Test
    void completeResponseLossRemainsUncertainEvenWhenHeadWouldMatch() {
        var operations = matchingExistingObject();
        operations.completeFailure = new IOException("response lost");
        var objects = new SdkMinioObjectClient(operations);

        assertThatThrownBy(() -> objects.putIfAbsent(
                BUCKET, KEY, new ByteArrayInputStream(new byte[]{1}), 1, "application/pdf"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MINIO_OBJECT_WRITE_UNCERTAIN")
                .hasNoCause();
        assertThat(operations.statCalls).isZero();
        assertThat(operations.aborted).isTrue();
    }

    @Test
    void abortFailureAddsOnlyFixedSafeSuppressedError() {
        var operations = new RecordingOperations();
        operations.completeFailure = new IOException("response lost with credential-secret");
        operations.abortFailure = new IOException("abort credential-secret");
        var objects = new SdkMinioObjectClient(operations);

        assertThatThrownBy(() -> objects.putIfAbsent(
                BUCKET, KEY, new ByteArrayInputStream(new byte[]{1}), 1, "application/pdf"))
                .hasMessage("MINIO_OBJECT_WRITE_UNCERTAIN")
                .hasNoCause()
                .satisfies(error -> assertThat(error.getSuppressed()).singleElement()
                        .satisfies(suppressed -> assertThat(suppressed)
                                .hasMessage("MINIO_MULTIPART_ABORT_FAILED").hasNoCause()));
    }

    @Test
    void readinessAlwaysAttemptsProbeCleanupAfterPutWasAttempted() {
        String probePattern = "/" + BUCKET + "/" + MinioReadinessHealthIndicator.PROBE_PREFIX + ".*";
        var simulatedPersistedObjects = ConcurrentHashMap.<String>newKeySet();
        server.addMockServiceRequestListener((request, response) -> {
            if (request.getUrl().startsWith("/" + BUCKET + "/" + MinioReadinessHealthIndicator.PROBE_PREFIX)) {
                if ("PUT".equals(request.getMethod().getName())) simulatedPersistedObjects.add(request.getUrl());
                if ("DELETE".equals(request.getMethod().getName())) simulatedPersistedObjects.remove(request.getUrl());
            }
        });
        server.stubFor(put(urlPathMatching(probePattern))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        server.stubFor(delete(urlPathMatching(probePattern)).willReturn(aResponse().withStatus(204)));

        assertThat(new MinioReadinessHealthIndicator(sync, BUCKET).health().getStatus().getCode())
                .isEqualTo("DOWN");
        server.verify(1, deleteRequestedFor(urlPathMatching(probePattern)));
        assertThat(simulatedPersistedObjects).isEmpty();
    }

    @Test
    void clientPropertiesToStringRedactsCredentialsAndWholeEndpoint() {
        var properties = new MinioClientProperties(
                "https://storage.internal.example:9443?token=endpoint-secret",
                "access-secret", "credential-secret", BUCKET);

        assertThat(properties.toString())
                .contains(BUCKET)
                .doesNotContain("storage.internal.example")
                .doesNotContain("token=")
                .doesNotContain("endpoint-secret")
                .doesNotContain("access-secret")
                .doesNotContain("credential-secret");
    }

    private void stubSuccessfulMultipart() {
        stubMultipartStartAndPart();
        server.stubFor(post(urlPathEqualTo("/" + BUCKET + "/" + KEY))
                .withQueryParam("uploadId", equalTo("upload-1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml")
                        .withBody("<CompleteMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                                + "<Location>http://localhost/" + BUCKET + "/" + KEY + "</Location>"
                                + "<Bucket>" + BUCKET + "</Bucket><Key>" + KEY + "</Key>"
                                + "<ETag>\"complete\"</ETag></CompleteMultipartUploadResult>")));
    }

    private void stubMultipartStartAndPart() {
        server.stubFor(post(urlPathEqualTo("/" + BUCKET + "/" + KEY))
                .withQueryParam("uploads", equalTo(""))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml")
                        .withBody("<InitiateMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                                + "<Bucket>" + BUCKET + "</Bucket><Key>" + KEY + "</Key>"
                                + "<UploadId>upload-1</UploadId></InitiateMultipartUploadResult>")));
        server.stubFor(put(urlPathEqualTo("/" + BUCKET + "/" + KEY))
                .withQueryParam("partNumber", equalTo("1"))
                .withQueryParam("uploadId", equalTo("upload-1"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"part-1\"")));
    }

    private static RecordingOperations matchingExistingObject() {
        var operations = new RecordingOperations();
        operations.stored = new MinioMultipartOperations.StoredObject(1, "application/pdf", SHA);
        return operations;
    }

    private static ErrorResponseException s3Failure(int status, String code) {
        var error = new ErrorResponse(code, "safe", BUCKET, KEY, "/" + BUCKET + "/" + KEY,
                "request", "host");
        var response = new Response.Builder()
                .request(new Request.Builder().url("http://127.0.0.1/" + BUCKET + "/" + KEY).build())
                .protocol(Protocol.HTTP_1_1).code(status).message("safe").build();
        return new ErrorResponseException(error, response, null);
    }

    private static final class RecordingOperations implements MinioMultipartOperations {
        private final List<Integer> partLengths = new ArrayList<>();
        private boolean begun;
        private boolean completed;
        private boolean aborted;
        private int statCalls;
        private Exception partFailure;
        private Exception completeFailure;
        private Exception abortFailure;
        private Exception statFailure;
        private StoredObject stored;

        @Override
        public String begin(String bucket, String key, String contentType, String sha256) {
            begun = true;
            return "upload-1";
        }

        @Override
        public UploadedPart uploadPart(
                String bucket, String key, String uploadId, int partNumber, byte[] bytes, int length) throws Exception {
            if (partFailure != null) throw partFailure;
            partLengths.add(length);
            return new UploadedPart(partNumber, "etag-" + partNumber);
        }

        @Override
        public void completeIfAbsent(
                String bucket, String key, String uploadId, List<UploadedPart> parts) throws Exception {
            if (completeFailure != null) throw completeFailure;
            completed = true;
        }

        @Override
        public void abort(String bucket, String key, String uploadId) throws Exception {
            aborted = true;
            if (abortFailure != null) throw abortFailure;
        }

        @Override
        public StoredObject stat(String bucket, String key) throws Exception {
            statCalls++;
            if (statFailure != null) throw statFailure;
            return stored;
        }
    }

    private static final class GeneratingInputStream extends InputStream {
        private long remaining;

        private GeneratingInputStream(long remaining) {
            this.remaining = remaining;
        }

        @Override
        public int read() {
            if (remaining == 0) return -1;
            remaining--;
            return 0x5a;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            if (remaining == 0) return -1;
            int count = (int) Math.min(remaining, length);
            java.util.Arrays.fill(bytes, offset, offset + count, (byte) 0x5a);
            remaining -= count;
            return count;
        }
    }
}
