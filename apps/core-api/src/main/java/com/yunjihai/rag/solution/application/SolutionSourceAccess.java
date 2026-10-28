package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.knowledge.KnowledgeSpaceReadAuthorization;
import com.yunjihai.rag.solution.domain.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class SolutionSourceAccess {
    private final KnowledgeSpaceReadAuthorization spaces;
    private final SolutionDocumentSourcePort documents;

    public SolutionSourceAccess(KnowledgeSpaceReadAuthorization spaces, SolutionDocumentSourcePort documents) {
        this.spaces = Objects.requireNonNull(spaces);
        this.documents = Objects.requireNonNull(documents);
    }

    public void requireCurrentSources(UUID actorId, UUID spaceId, RequirementVersion version, Instant at) {
        Objects.requireNonNull(version);
        try {
            spaces.requireRead(actorId, spaceId, at);
        } catch (AccessDeniedAsNotFoundException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new SolutionAuthorityUnavailableException();
        }
        for (var item : version.items()) {
            if (item.category() != RequirementCategory.UNKNOWN && item.sources().isEmpty()) {
                throw new AccessDeniedAsNotFoundException();
            }
            for (var source : item.sources()) {
                var reference = source.documentVersion();
                final boolean readable;
                try {
                    readable = documents.isCurrentPublishedAndReadable(actorId, spaceId,
                            reference.documentId(), reference.versionId(), at);
                } catch (RuntimeException unavailable) {
                    throw new SolutionAuthorityUnavailableException();
                }
                if (!readable) {
                    throw new AccessDeniedAsNotFoundException();
                }
            }
        }
    }
}
