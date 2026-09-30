package com.yunjihai.rag.infrastructure.opensearch;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Requests;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.Objects;

public final class OpenSearchReadHealthIndicator implements HealthIndicator {
    private final OpenSearchClient client;

    public OpenSearchReadHealthIndicator(OpenSearchClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public Health health() {
        try (var response = client.generic().execute(
                Requests.builder().method("GET").endpoint("/_cluster/health").build())) {
            return response.getStatus() >= 200 && response.getStatus() < 300
                    ? Health.up().build()
                    : Health.down().withDetail("search", "unavailable").build();
        } catch (Exception failure) {
            return Health.down().withDetail("search", "unavailable").build();
        }
    }
}
