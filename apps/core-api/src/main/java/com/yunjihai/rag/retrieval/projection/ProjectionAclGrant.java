package com.yunjihai.rag.retrieval.projection;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ProjectionAclGrant(String principalKey, Instant expiresAt) {
    public ProjectionAclGrant {
        Objects.requireNonNull(principalKey);
        String[] parts = principalKey.split(":", -1);
        try {
            if (parts.length != 2
                    || !Set.of("USER", "DEPARTMENT", "ROLE", "PROJECT_GROUP").contains(parts[0])) {
                throw new IllegalArgumentException();
            }
            UUID.fromString(parts[1]);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("投影 ACL 主体键无效");
        }
    }
}
