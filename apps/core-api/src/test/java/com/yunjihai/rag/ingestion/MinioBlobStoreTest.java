package com.yunjihai.rag.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinioBlobStoreTest {

    @Test
    @DisplayName("内容寻址键稳定且已存在对象不会被重复写入")
    void writesContentAddressedObjectOnlyOnce() {
        var client = new RecordingMinioClient();
        var cleanup = new RecordingCleanup();
        var store = new MinioBlobStore("rag-blobs", client, cleanup);

        var first = store.putIfAbsent(
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                new ByteArrayInputStream(new byte[]{1, 2}), 2, MediaType.APPLICATION_PDF);
        var second = store.putIfAbsent(
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                new ByteArrayInputStream(new byte[]{9, 9}), 2, MediaType.APPLICATION_PDF);

        assertThat(first.objectKey()).isEqualTo(
                "sha256/ab/abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(client.objects).containsEntry(
                "rag-blobs/sha256/ab/abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                new byte[]{1, 2});
        store.scheduleCleanup(first);
        assertThat(cleanup.recorded).isEqualTo(1);
        assertThat(client.objects).hasSize(1);
    }

    @Test
    @DisplayName("非法 SHA-256 不进入对象存储")
    void rejectsInvalidSha256() {
        var store = new MinioBlobStore("rag-blobs", new RecordingMinioClient(), new RecordingCleanup());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.putIfAbsent(
                        "abc123", new ByteArrayInputStream(new byte[]{1}), 1, MediaType.APPLICATION_PDF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("并发写入相同哈希时仅一个调用创建对象")
    void conditionallyCreatesSameObjectOnceUnderConcurrency() throws Exception {
        var client = new RecordingMinioClient();
        var store = new MinioBlobStore("rag-blobs", client, new RecordingCleanup());
        var hash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0, 32)
                    .mapToObj(index -> executor.submit(() -> store.putIfAbsent(
                            hash, new ByteArrayInputStream(new byte[]{(byte) index}), 1, MediaType.APPLICATION_PDF)))
                    .toList();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(futures.stream().filter(future -> {
                try {
                    return future.get().created();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            })).hasSize(1);
        }
        assertThat(client.objects).hasSize(1);
    }

    @Test
    @DisplayName("响应丢失时保守登记补偿候选并保持写入失败")
    void uncertainWriteRegistersCleanupCandidateBeforeRethrowing() {
        String hash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        var cleanup = new RecordingCleanup();
        var failure = new IllegalStateException("MINIO_OBJECT_WRITE_UNCERTAIN", null);
        var store = new MinioBlobStore("rag-blobs", (bucket, key, stream, length, type) -> {
            throw failure;
        }, cleanup);

        assertThatThrownBy(() -> store.putIfAbsent(
                hash, new ByteArrayInputStream(new byte[]{1}), 1, MediaType.APPLICATION_PDF))
                .isSameAs(failure)
                .hasNoCause();
        assertThat(cleanup.recorded).isEqualTo(1);
        assertThat(cleanup.last).isEqualTo(new OrphanBlobCandidate(
                "rag-blobs", hash, "sha256/ab/" + hash));
    }

    @Test
    @DisplayName("补偿登记自身失败时仅附加固定安全异常")
    void recorderFailureCannotLeakItsCauseFromUncertainWrite() {
        String hash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        var uncertain = new IllegalStateException("MINIO_OBJECT_WRITE_UNCERTAIN", null);
        var secretFailure = new IllegalStateException("jdbc://secret-host?password=secret");
        var store = new MinioBlobStore("rag-blobs", (bucket, key, stream, length, type) -> {
            throw uncertain;
        }, candidate -> {
            throw secretFailure;
        });

        assertThatThrownBy(() -> store.putIfAbsent(
                hash, new ByteArrayInputStream(new byte[]{1}), 1, MediaType.APPLICATION_PDF))
                .isSameAs(uncertain)
                .hasNoCause()
                .satisfies(error -> assertThat(error.getSuppressed())
                        .singleElement()
                        .satisfies(suppressed -> {
                            assertThat(suppressed).hasMessage("BLOB_CLEANUP_RECORD_FAILED").hasNoCause();
                            assertThat(suppressed.getMessage()).doesNotContain("secret");
                        }));
    }

    private static final class RecordingMinioClient implements MinioObjectClient {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override
        public boolean putIfAbsent(String bucket, String key, InputStream stream, long length, String contentType) {
            try {
                var fullKey = bucket + "/" + key;
                return objects.putIfAbsent(fullKey, stream.readAllBytes()) == null;
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private static final class RecordingCleanup implements OrphanBlobRecorder {
        private int recorded;
        private OrphanBlobCandidate last;

        @Override
        public void record(OrphanBlobCandidate candidate) {
            recorded++;
            last = candidate;
        }
    }
}
