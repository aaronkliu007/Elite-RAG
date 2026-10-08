package com.yunjihai.rag.identity.local;

import java.time.Instant;

public interface LoginSourceRateLimiter {
    void assertAllowed(String sourceIp, Instant at);
    void recordFailure(String sourceIp, Instant at);
    void reset(String sourceIp);
}
