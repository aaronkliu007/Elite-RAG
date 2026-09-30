package com.yunjihai.rag.infrastructure.opensearch;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public record OpenSearchProperties(
        URI endpoint,
        String username,
        String password,
        Duration connectTimeout,
        Duration responseTimeout) {
    public OpenSearchProperties(URI endpoint, String username, String password) {
        this(endpoint, username, password, Duration.ofSeconds(3), Duration.ofSeconds(10));
    }

    public OpenSearchProperties {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(responseTimeout, "responseTimeout");
        boolean http = "http".equalsIgnoreCase(endpoint.getScheme());
        boolean https = "https".equalsIgnoreCase(endpoint.getScheme());
        if ((!http && !https) || endpoint.getHost() == null
                || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("OpenSearch endpoint must be an HTTP(S) origin");
        }
        boolean hasUsername = username != null && !username.isBlank();
        boolean hasPassword = password != null && !password.isBlank();
        if (hasUsername != hasPassword) {
            throw new IllegalArgumentException("OpenSearch username and password must be configured together");
        }
        if (hasUsername && !https) {
            throw new IllegalArgumentException("OpenSearch basic authentication requires HTTPS");
        }
        username = hasUsername ? username.strip() : null;
        password = hasPassword ? password : null;
    }

    public boolean authenticated() {
        return username != null;
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    @Override
    public String toString() {
        return "OpenSearchProperties[endpoint=" + endpoint + ", username=" + username
                + ", password=" + (password == null ? null : "[REDACTED]")
                + ", connectTimeout=" + connectTimeout
                + ", responseTimeout=" + responseTimeout + "]";
    }
}
