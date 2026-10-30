package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.RetrievalService;
import com.yunjihai.rag.retrieval.domain.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Performs one version-scoped retrieval per document before any reranker receives excerpts. */
public final class ScopedDocumentEvidenceRetrieval implements DocumentEvidenceRetrievalPort {
    private final RetrievalService retrieval;
    public ScopedDocumentEvidenceRetrieval(RetrievalService retrieval) { this.retrieval = Objects.requireNonNull(retrieval); }

    @Override public EvidencePack retrieve(RetrievalQuery query, Set<DocumentVersionRef> allowedVersions) {
        Objects.requireNonNull(query);
        var versions = new ArrayList<>(Set.copyOf(Objects.requireNonNull(allowedVersions)));
        if (versions.isEmpty() || versions.size() > 10 || query.spaceIds().size() != 1
                || !query.metadataFilter().isEmpty()) throw new IllegalArgumentException("invalid document scope");
        versions.sort(Comparator.comparing(DocumentVersionRef::documentId).thenComparing(DocumentVersionRef::versionId));
        var groups = new ArrayList<List<Evidence>>();
        var reasons = EnumSet.noneOf(DegradationReason.class);
        int lexical = 0, vector = 0, fused = 0, reauthorized = 0;
        Instant completed = Instant.EPOCH;
        for (var version : versions) {
            var scopedId = UUID.nameUUIDFromBytes((query.requestId() + ":" + version.documentId()
                    + ":" + version.versionId()).getBytes(StandardCharsets.UTF_8));
            var scoped = new RetrievalQuery(scopedId, query.userId(), query.spaceIds(), query.queryText(),
                    Map.of("documentId", version.documentId().toString(), "versionId", version.versionId().toString()),
                    query.requestedEvidenceCount());
            var pack = Objects.requireNonNull(retrieval.retrieve(scoped));
            if (!scopedId.equals(pack.requestId())) throw new IllegalStateException("RETRIEVAL_REQUEST_MISMATCH");
            groups.add(pack.evidence().stream().filter(item -> query.spaceIds().contains(item.spaceId())
                    && item.documentId().equals(version.documentId()) && item.versionId().equals(version.versionId())).toList());
            reasons.addAll(pack.degradationReasons());
            lexical = Math.addExact(lexical, pack.stats().lexicalCount());
            vector = Math.addExact(vector, pack.stats().vectorCount());
            fused = Math.addExact(fused, pack.stats().fusedCount());
            reauthorized = Math.addExact(reauthorized, pack.stats().reauthorizedCount());
            if (pack.completedAt().isAfter(completed)) completed = pack.completedAt();
        }
        var combined = new ArrayList<Evidence>();
        for (int index = 0; index < 15 && combined.size() < query.requestedEvidenceCount(); index++) {
            for (var group : groups) {
                if (index < group.size()) combined.add(group.get(index));
                if (combined.size() == query.requestedEvidenceCount()) break;
            }
        }
        return new EvidencePack(query.requestId(), combined, !reasons.isEmpty(), reasons,
                new RetrievalStats(lexical, vector, fused, reauthorized, combined.size()), completed);
    }
}
