package com.yunjihai.rag.infrastructure.model;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import java.util.Objects;

public final class ModelGatewayHealthIndicator implements HealthIndicator {
    private static final Status DISABLED = new Status("DISABLED");
    private final ApprovedModelGatewayProperties properties;

    public ModelGatewayHealthIndicator(ApprovedModelGatewayProperties properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public Health health() {
        return properties.enabled()
                ? Health.up().withDetail("capability", "ENABLED").build()
                : Health.status(DISABLED).withDetail("capability", "DISABLED").build();
    }
}
