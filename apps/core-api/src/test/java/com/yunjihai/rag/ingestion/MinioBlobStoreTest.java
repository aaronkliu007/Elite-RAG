package com.yunjihai.rag.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MinioBlobStoreTest {

    @Test
    @DisplayName("内容寻址键稳定且已存在对象不会被重复写入")
    void writesContentAddressedObjectOnlyOnce() {
        var client = new RecordingMinioClient();
        var store = new MinioBlobStore("rag-blobs", client);

        var first = store.putIfAbsent(
                "abc123", new ByteArrayInputStream(new byte[]{1, 2}), 2, MediaType.APPLICATION_PDF);
        var second = store.putIfAbsent(
                "abc123", new ByteArrayInputStream(new byte[]{9, 9}), 2, MediaType.APPLICATION_PDF);

        assertThat(first.objectKey()).isEqualTo("sha256/ab/abc123");
        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(client.objects).containsEntry("rag-blobs/sha256/ab/abc123", new byte[]{1, 2});
    }

    private static final class RecordingMinioClient implements MinioObjectClient {
        private final Map<String, byte[]> objects = new HashMap<>();

        @Override
        public boolean putIfAbsent(String bucket, String key, InputStream stream, long length, String contentType) {
            try {
                var fullKey = bucket + "/" + key;
                if (objects.containsKey(fullKey)) {
                    return false;
                }
                objects.put(fullKey, stream.readAllBytes());
                return true;
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public void deleteIfUnreferenced(String bucket, String key) {
            objects.remove(bucket + "/" + key);
        }
    }
}
