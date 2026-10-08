package com.yunjihai.rag.identity.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("rag.session")
public class SessionProperties {
    private Duration absoluteTimeout = Duration.ofHours(8);

    public Duration getAbsoluteTimeout() {
        return absoluteTimeout;
    }

    public void setAbsoluteTimeout(Duration absoluteTimeout) {
        if (absoluteTimeout == null || absoluteTimeout.isZero() || absoluteTimeout.isNegative()) {
            throw new IllegalArgumentException("Session 绝对有效期必须大于零");
        }
        this.absoluteTimeout = absoluteTimeout;
    }
}
