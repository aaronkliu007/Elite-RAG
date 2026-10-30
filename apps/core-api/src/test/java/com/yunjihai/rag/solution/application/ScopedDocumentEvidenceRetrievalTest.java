package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.domain.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ScopedDocumentEvidenceRetrievalTest {
    private static UUID id(long value) { return new UUID(0, value); }
    @Test void scopesEveryQueryBeforeRetrievalAndGivesEachSelectedDocumentEvidence() {
        var selected = Set.of(new DocumentVersionRef(id(10), id(11)), new DocumentVersionRef(id(20), id(21)));
        var seen = new ArrayList<RetrievalQuery>();
        var adapter = new ScopedDocumentEvidenceRetrieval(query -> {
            seen.add(query);
            var doc = UUID.fromString(query.metadataFilter().get("documentId"));
            var version = UUID.fromString(query.metadataFilter().get("versionId"));
            var evidence = new ArrayList<Evidence>();
            for (int i = 0; i < 15; i++) evidence.add(new Evidence(id(2), UUID.randomUUID(), doc, version,
                    "node-" + i, "需求文档", "内容", 0.5, new MarkdownLocator(List.of("范围"), "node-" + i)));
            return new EvidencePack(query.requestId(), evidence, false, Set.of(), new RetrievalStats(15, 0, 15, 15, 15), Instant.EPOCH);
        });
        var request = new RetrievalQuery(id(1), id(3), Set.of(id(2)), "项目需求", Map.of(), 15);
        var result = adapter.retrieve(request, selected);
        assertThat(seen).hasSize(2);
        assertThat(seen).allSatisfy(query -> assertThat(selected).contains(new DocumentVersionRef(
                UUID.fromString(query.metadataFilter().get("documentId")), UUID.fromString(query.metadataFilter().get("versionId")))));
        assertThat(result.requestId()).isEqualTo(request.requestId());
        assertThat(result.evidence()).hasSize(15).extracting(Evidence::documentId).contains(id(10), id(20));
    }
}
