package com.yunjihai.rag.solution.port;

import com.yunjihai.rag.solution.domain.OutlineVersion;

import java.util.Optional;
import java.util.UUID;

public interface OutlineVersionRepository {

    Optional<OutlineVersion> findById(UUID outlineVersionId);

    void insert(OutlineVersion outlineVersion);
}
