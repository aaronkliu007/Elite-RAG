package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.domain.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class DocumentRequirementEvidenceServiceTest {
    private static final UUID ACTOR = id(1), SPACE = id(2), REQUEST = id(3);
    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");
    private static final DocumentVersionRef SELECTED = new DocumentVersionRef(id(4), id(5));
    private static final DocumentVersionRef MISSING = new DocumentVersionRef(id(6), id(7));

    @Test void returnsOnlySelectedVersionsAndReportsSelectionsWithoutEvidence() {
        var query = new AtomicReference<RetrievalQuery>();
        var checks = new AtomicInteger();
        var service = new DocumentRequirementEvidenceService((input, allowed) -> {
            query.set(input);
            assertThat(allowed).containsExactlyInAnyOrder(SELECTED, MISSING);
            return pack(List.of(evidence(SELECTED), evidence(new DocumentVersionRef(id(8), id(9)))));
        }, (actor, space, document, version, at) -> { checks.incrementAndGet(); return true; },
                Clock.fixed(NOW, ZoneOffset.UTC));
        var result = service.select(REQUEST, ACTOR, SPACE, "业务需求", List.of(SELECTED, MISSING));
        assertThat(result.evidence()).extracting(Evidence::documentId).containsExactly(SELECTED.documentId());
        assertThat(result.documentsWithoutEvidence()).containsExactly(MISSING);
        assertThat(checks).hasValue(4);
        assertThat(query.get().userId()).isEqualTo(ACTOR);
        assertThat(query.get().spaceIds()).containsExactly(SPACE);
        assertThat(query.get().requestedEvidenceCount()).isEqualTo(15);
    }

    @Test void rejectsUnauthorizedDocumentBeforeRetrievalAndRevocationAfterRetrieval() {
        var calls = new AtomicInteger();
        var denied = new DocumentRequirementEvidenceService((input, allowed) -> { calls.incrementAndGet(); return pack(List.of()); },
                (actor, space, document, version, at) -> false, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> denied.select(REQUEST, ACTOR, SPACE, "业务需求", List.of(SELECTED)))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThat(calls).hasValue(0);
        var checks = new AtomicInteger();
        var revoked = new DocumentRequirementEvidenceService((input, allowed) -> pack(List.of(evidence(SELECTED))),
                (actor, space, document, version, at) -> checks.incrementAndGet() == 1,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> revoked.select(REQUEST, ACTOR, SPACE, "业务需求", List.of(SELECTED)))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
    }

    @Test void requiresBoundedDistinctSelectionsAndDoesNotLeakEvidenceInToString() {
        var service = new DocumentRequirementEvidenceService((input, allowed) -> pack(List.of(evidence(SELECTED))),
                (actor, space, document, version, at) -> true, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> service.select(REQUEST, ACTOR, SPACE, "需求", List.of(SELECTED, SELECTED)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.select(REQUEST, ACTOR, SPACE, "需求", List.of(SELECTED)).toString())
                .doesNotContain("客户秘密");
    }

    private static Evidence evidence(DocumentVersionRef ref) {
        return new Evidence(SPACE, UUID.randomUUID(), ref.documentId(), ref.versionId(), "node", "客户文件",
                "客户秘密", 0.8, new MarkdownLocator(List.of("需求"), "node"));
    }
    private static EvidencePack pack(List<Evidence> evidence) {
        return new EvidencePack(REQUEST, evidence, false, Set.of(), new RetrievalStats(0, 0, 0, 0, evidence.size()), NOW);
    }
    private static UUID id(long value) { return new UUID(0, value); }
}
