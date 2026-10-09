package com.yunjihai.rag.ingestion;

import java.util.Objects;
import java.util.UUID;
import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;

@FunctionalInterface
public interface DocumentDraftGateway {

    default void authorize(UUID spaceId, UUID actorId) {
        throw new AccessDeniedAsNotFoundException();
    }

    DraftResult createDraft(ValidatedUpload command);

    record DraftResult(DocumentVersionId version, boolean created) {
        public DraftResult {
            Objects.requireNonNull(version);
        }
    }
}
