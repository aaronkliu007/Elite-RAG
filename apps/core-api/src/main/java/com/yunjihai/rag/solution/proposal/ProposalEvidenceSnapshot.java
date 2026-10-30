package com.yunjihai.rag.solution.proposal;

import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.SourceLocator;
import com.yunjihai.rag.solution.generation.GenerationEvidenceSource;
import java.util.*;

/** Immutable source identities and hashes only; document excerpts are never stored here. */
public record ProposalEvidenceSnapshot(UUID spaceId, List<DocumentVersionRef> documents, List<Source> sources) {
    public ProposalEvidenceSnapshot {
        Objects.requireNonNull(spaceId);
        documents = List.copyOf(Objects.requireNonNull(documents));
        sources = List.copyOf(Objects.requireNonNull(sources));
        if (documents.isEmpty() || documents.size() > 10 || new HashSet<>(documents).size() != documents.size()
                || sources.isEmpty() || sources.size() > 15) throw new IllegalArgumentException("invalid proposal snapshot size");
        var ids = new HashSet<UUID>();
        for (int i = 0; i < sources.size(); i++) {
            var source = sources.get(i);
            if (source.position() != i + 1 || !documents.contains(source.document()) || !ids.add(source.evidenceId()))
                throw new IllegalArgumentException("invalid proposal snapshot source");
        }
    }
    public static ProposalEvidenceSnapshot from(UUID spaceId, List<DocumentVersionRef> documents, List<Evidence> evidence) {
        Objects.requireNonNull(evidence);
        var sources = new ArrayList<Source>();
        for (int i = 0; i < evidence.size(); i++) {
            var item = evidence.get(i);
            if (!spaceId.equals(item.spaceId())) throw new IllegalArgumentException("source space mismatch");
            var identity = GenerationEvidenceSource.from(i + 1, item);
            sources.add(new Source(i + 1, item.evidenceId(), new DocumentVersionRef(item.documentId(), item.versionId()),
                    item.nodeId(), item.locator(), identity.excerptHash()));
        }
        return new ProposalEvidenceSnapshot(spaceId, documents, sources);
    }
    @Override public String toString() { return "ProposalEvidenceSnapshot[documentCount=" + documents.size()
            + ", sourceCount=" + sources.size() + "]"; }
    public record Source(int position, UUID evidenceId, DocumentVersionRef document,
            String nodeId, SourceLocator locator, String excerptHash) {
        public Source {
            if (position < 1 || position > 15) throw new IllegalArgumentException("invalid source position");
            Objects.requireNonNull(evidenceId); Objects.requireNonNull(document); Objects.requireNonNull(locator);
            if (nodeId == null || nodeId.isBlank() || nodeId.length() > 200
                    || excerptHash == null || !excerptHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid snapshot source");
        }
    }
}
