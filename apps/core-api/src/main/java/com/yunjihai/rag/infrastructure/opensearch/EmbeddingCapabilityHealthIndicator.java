package com.yunjihai.rag.infrastructure.opensearch;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

public final class EmbeddingCapabilityHealthIndicator implements HealthIndicator {
    private final boolean enabled;

    public EmbeddingCapabilityHealthIndicator(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public Health health() {
        return Health.up().withDetail("capability", enabled ? "ENABLED" : "DISABLED").build();
    }
}
