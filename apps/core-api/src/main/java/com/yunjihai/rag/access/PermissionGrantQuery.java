package com.yunjihai.rag.access;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface PermissionGrantQuery {
    List<Grant> listForSpace(UUID spaceId, int offset, int limit);

    record Grant(UUID id, String principalType, UUID principalId, String username,
                 String displayName, String source, OffsetDateTime expiresAt) { }
}
