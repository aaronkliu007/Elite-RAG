package com.yunjihai.rag.knowledge;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface DocumentParseErrorQuery {
    Optional<String> findByVersionId(UUID versionId);

    static DocumentParseErrorQuery none() {
        return versionId -> Optional.empty();
    }
}
