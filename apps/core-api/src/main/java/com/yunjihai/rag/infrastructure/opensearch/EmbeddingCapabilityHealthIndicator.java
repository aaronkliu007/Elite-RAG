package com.yunjihai.rag.infrastructure.opensearch;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

public final class EmbeddingCapabilityHealthIndicator implements HealthIndicator {
    private final boolean enabled;
    private final int dimension;

    public EmbeddingCapabilityHealthIndicator(boolean enabled, int dimension) {
        if (enabled && dimension < 1) {
            throw new IllegalArgumentException("vector dimension must be positive when enabled");
        }
        this.enabled = enabled;
        this.dimension = dimension;
    }

    @Override
    public Health health() {
        var health = Health.up().withDetail("capability", enabled ? "ENABLED" : "DISABLED");
        if (enabled) health.withDetail("dimension", dimension);
        return health.build();
    }
}
