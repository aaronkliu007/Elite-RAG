package com.yunjihai.rag.infrastructure.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.Requests;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.Objects;

public final class OpenSearchReadHealthIndicator implements HealthIndicator {
    private final OpenSearchClient client;
    private final ObjectMapper json = new ObjectMapper();

    public OpenSearchReadHealthIndicator(OpenSearchClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public Health health() {
        try {
            var cluster = request(Requests.builder().method("GET").endpoint("/_cluster/health").build());
            String status = cluster.path("status").asText("");
            if ("red".equals(status) || !("green".equals(status) || "yellow".equals(status))) {
                return Health.down().withDetail("search", "cluster-" + status).build();
            }
            var probe = Requests.builder().method("POST")
                    .endpoint("/" + OpenSearchIndexManager.READ_ALIAS + "/_search")
                    .json("{\"size\":0,\"track_total_hits\":false,\"query\":{\"match_all\":{}}}")
                    .build();
            request(probe);
            return Health.up().withDetail("clusterStatus", status).build();
        } catch (Exception failure) {
            return Health.down().withDetail("search", "unavailable").build();
        }
    }

    private com.fasterxml.jackson.databind.JsonNode request(
            org.opensearch.client.opensearch.generic.Request request) throws Exception {
        try (var response = client.generic().execute(request)) {
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                throw new IllegalStateException("OPENSEARCH_HEALTH_REQUEST_FAILED");
            }
            return json.readTree(response.getBody().map(Body::bodyAsString).orElse("{}"));
        }
    }
}
