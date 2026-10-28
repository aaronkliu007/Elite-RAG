package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.solution.generation.GenerationEvidenceAuthorizationPort;
import com.yunjihai.rag.solution.generation.GenerationEvidenceSource;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class CurrentGenerationEvidenceAuthorization implements GenerationEvidenceAuthorizationPort {
    private final SolutionDocumentSourcePort documents;

    public CurrentGenerationEvidenceAuthorization(SolutionDocumentSourcePort documents) {
        this.documents = Objects.requireNonNull(documents);
    }

    @Override
    public void requireReadable(UUID actorId, UUID spaceId, List<GenerationEvidenceSource> evidence, Instant at) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(evidence);
        Objects.requireNonNull(at);
        if (evidence.isEmpty()) throw new AccessDeniedAsNotFoundException();
        var checked = new HashSet<DocumentVersionRef>();
        for (var item : evidence) {
            if (!item.spaceId().equals(spaceId)) throw new AccessDeniedAsNotFoundException();
            var reference = new DocumentVersionRef(item.documentId(), item.versionId());
            if (!checked.add(reference)) continue;
            final boolean readable;
            try {
                readable = documents.isCurrentPublishedAndReadable(actorId, spaceId,
                        reference.documentId(), reference.versionId(), at);
            } catch (RuntimeException unavailable) {
                throw new SolutionAuthorityUnavailableException();
            }
            if (!readable) throw new AccessDeniedAsNotFoundException();
        }
    }
}
