package com.yunjihai.rag.infrastructure.minio;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public final class MinioReadinessHealthIndicator implements HealthIndicator {
    static final String PROBE_PREFIX = "__system/readiness/";
    private static final byte[] PROBE_BODY = new byte[]{0x52, 0x41, 0x47};
    private final MinioClient client;
    private final String bucket;

    public MinioReadinessHealthIndicator(MinioClient client, String bucket) {
        this.client = Objects.requireNonNull(client);
        if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("MINIO_CONFIGURATION_INVALID");
        this.bucket = bucket;
    }

    @Override
    public Health health() {
        String key = PROBE_PREFIX + UUID.randomUUID();
        boolean created = false;
        boolean deleted = false;
        try {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(new ByteArrayInputStream(PROBE_BODY), PROBE_BODY.length, -1)
                    .contentType("application/octet-stream")
                    .build());
            created = true;
            try (var value = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
                if (!Arrays.equals(PROBE_BODY, value.readAllBytes())) return down();
            }
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
            deleted = objectIsAbsent(key);
            return deleted ? Health.up().build() : down();
        } catch (Exception failure) {
            return down();
        } finally {
            if (created && !deleted) {
                try {
                    client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
                } catch (Exception ignored) {
                    // A failed cleanup leaves readiness DOWN; never expose SDK details.
                }
            }
        }
    }

    private boolean objectIsAbsent(String key) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return false;
        } catch (ErrorResponseException failure) {
            String code = failure.errorResponse() == null ? null : failure.errorResponse().code();
            return "NoSuchKey".equals(code) || "NoSuchObject".equals(code);
        } catch (Exception failure) {
            return false;
        }
    }

    private static Health down() {
        return Health.down().withDetail("storage", "unavailable").build();
    }
}
