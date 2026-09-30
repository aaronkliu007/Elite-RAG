package com.yunjihai.rag.infrastructure.opensearch;

import java.net.URI;
import java.util.Objects;

public record OpenSearchProperties(URI endpoint, String username, String password) {
    public OpenSearchProperties {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
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

    @Override
    public String toString() {
        return "OpenSearchProperties[endpoint=" + endpoint + ", username=" + username
                + ", password=" + (password == null ? null : "[REDACTED]") + "]";
    }
}
