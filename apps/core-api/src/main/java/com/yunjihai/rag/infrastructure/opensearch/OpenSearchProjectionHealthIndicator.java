package com.yunjihai.rag.infrastructure.opensearch;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.Objects;

public final class OpenSearchProjectionHealthIndicator implements HealthIndicator {
    private final OpenSearchIndexManager indexes;

    public OpenSearchProjectionHealthIndicator(OpenSearchIndexManager indexes) {
        this.indexes = Objects.requireNonNull(indexes);
    }

    @Override
    public Health health() {
        try {
            return indexes.aliasTarget(OpenSearchIndexManager.WRITE_ALIAS).isPresent()
                    ? Health.up().build()
                    : Health.down().withDetail("projection", "write_alias_missing").build();
        } catch (RuntimeException failure) {
            return Health.down().withDetail("projection", "unavailable").build();
        }
    }
}
