package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.RetrievalQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Selects bounded evidence from explicitly authorized current document versions. */
public final class DocumentRequirementEvidenceService {
    private final DocumentEvidenceRetrievalPort retrieval;
    private final SolutionDocumentSourcePort authorization;
    private final Clock clock;

    public DocumentRequirementEvidenceService(DocumentEvidenceRetrievalPort retrieval,
            SolutionDocumentSourcePort authorization, Clock clock) {
        this.retrieval = Objects.requireNonNull(retrieval);
        this.authorization = Objects.requireNonNull(authorization);
        this.clock = Objects.requireNonNull(clock);
    }

    public Selection select(UUID requestId, UUID actor, UUID space, String query,
            List<DocumentVersionRef> documents) {
        Objects.requireNonNull(requestId); Objects.requireNonNull(actor); Objects.requireNonNull(space);
        var selected = List.copyOf(Objects.requireNonNull(documents));
        if (selected.isEmpty() || selected.size() > 10 || new HashSet<>(selected).size() != selected.size())
            throw new IllegalArgumentException("invalid selected documents");
        var request = new RetrievalQuery(requestId, actor, Set.of(space), query, Map.of(), 15);
        requireDocuments(actor, space, selected, clock.instant());
        var pack = Objects.requireNonNull(retrieval.retrieve(request, Set.copyOf(selected)));
        if (!requestId.equals(pack.requestId())) throw new IllegalStateException("RETRIEVAL_REQUEST_MISMATCH");
        requireDocuments(actor, space, selected, clock.instant());
        var allowed = Set.copyOf(selected);
        var evidence = pack.evidence().stream().filter(item -> item.spaceId().equals(space)
                && allowed.contains(new DocumentVersionRef(item.documentId(), item.versionId()))).toList();
        var found = evidence.stream().map(item -> new DocumentVersionRef(item.documentId(), item.versionId()))
                .collect(Collectors.toUnmodifiableSet());
        var missing = selected.stream().filter(document -> !found.contains(document)).toList();
        return new Selection(evidence, missing, pack.degraded());
    }

    private void requireDocuments(UUID actor, UUID space, List<DocumentVersionRef> documents, Instant at) {
        for (var document : documents) {
            final boolean allowed;
            try { allowed = authorization.isCurrentPublishedAndReadable(actor, space,
                    document.documentId(), document.versionId(), at); }
            catch (AccessDeniedAsNotFoundException denied) { throw denied; }
            catch (RuntimeException unavailable) { throw new SolutionAuthorityUnavailableException(); }
            if (!allowed) throw new AccessDeniedAsNotFoundException();
        }
    }

    public record Selection(List<Evidence> evidence, List<DocumentVersionRef> documentsWithoutEvidence,
            boolean degraded) {
        public Selection {
            evidence = List.copyOf(Objects.requireNonNull(evidence));
            documentsWithoutEvidence = List.copyOf(Objects.requireNonNull(documentsWithoutEvidence));
        }
        @Override public String toString() {
            return "Selection[evidenceCount=" + evidence.size() + ", documentsWithoutEvidenceCount="
                    + documentsWithoutEvidence.size() + ", degraded=" + degraded + "]";
        }
    }
}
