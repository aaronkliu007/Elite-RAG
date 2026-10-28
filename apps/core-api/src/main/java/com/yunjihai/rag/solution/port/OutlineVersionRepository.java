package com.yunjihai.rag.solution.port;

import com.yunjihai.rag.solution.domain.OutlineVersion;

import java.util.Optional;
import java.util.UUID;

public interface OutlineVersionRepository {

    Optional<OutlineVersion> findById(UUID outlineVersionId);

    default long nextNumber(UUID solutionId) {
        throw new UnsupportedOperationException("outline numbering is not available");
    }

    void insert(OutlineVersion outlineVersion);

    default void insert(UUID solutionId, OutlineVersion outlineVersion) {
        insert(outlineVersion);
    }
}
