package com.yunjihai.rag.infrastructure.minio;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties("rag.minio")
public record MinioClientProperties(String endpoint, String accessKey, String secretKey, String bucket) {

    public MinioClientProperties {
        requireText(endpoint);
        requireText(accessKey);
        requireText(secretKey);
        requireText(bucket);
    }

    public MinioClient client() {
        try {
            URI uri = URI.create(endpoint);
            if (uri.getScheme() == null || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
                throw configurationFailure();
            }
            return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        } catch (RuntimeException failure) {
            throw configurationFailure();
        }
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw configurationFailure();
    }

    private static IllegalArgumentException configurationFailure() {
        return new IllegalArgumentException("MINIO_CONFIGURATION_INVALID", null);
    }
}
