package com.yunjihai.rag.infrastructure.model;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ModelGatewayHealthIndicatorTest {

    @Test
    void reportsDisabledAsAnExplicitNonUpCapability() {
        var properties = new ApprovedModelGatewayProperties(
                false, null, null, null, Set.of(), null, null, null, null,
                null, null, null, 0, 0, 0, 0, false);

        var health = new ModelGatewayHealthIndicator(properties).health();

        assertThat(health.getStatus()).isEqualTo(new Status("DISABLED"));
        assertThat(health.getDetails()).containsExactlyEntriesOf(Map.of("capability", "DISABLED"));
    }

    @Test
    void enabledHealthExposesNoEndpointCredentialOrModelConfiguration() {
        var properties = new ApprovedModelGatewayProperties(
                true, "approved-openai-compatible", "model-config-v1",
                URI.create("https://approved-model.example"), Set.of("approved-model.example"),
                "health-secret-api-key", "embedding-private-name", "reranker-private-name",
                "chat-private-name", Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(3), 8_000, 2_000, 4, 100_000, false);

        var health = new ModelGatewayHealthIndicator(properties).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsExactlyEntriesOf(Map.of("capability", "ENABLED"));
        assertThat(health.toString())
                .doesNotContain("approved-model.example")
                .doesNotContain("health-secret-api-key")
                .doesNotContain("embedding-private-name")
                .doesNotContain("reranker-private-name")
                .doesNotContain("chat-private-name");
    }
}
