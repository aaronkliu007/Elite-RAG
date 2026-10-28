package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import com.yunjihai.rag.solution.generation.GenerationEvidenceSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class CurrentGenerationEvidenceAuthorizationTest {
    @Test void checksEveryDistinctCurrentDocumentAndRejectsCrossSpaceEvidence() {
        var actor = UUID.randomUUID();
        var space = UUID.randomUUID();
        var document = UUID.randomUUID();
        var version = UUID.randomUUID();
        var at = Instant.parse("2026-10-28T10:00:00Z");
        var calls = new AtomicInteger();
        var access = new CurrentGenerationEvidenceAuthorization(
                (user, targetSpace, targetDocument, targetVersion, checkedAt) -> {
                    calls.incrementAndGet();
                    return user.equals(actor) && targetSpace.equals(space)
                            && targetDocument.equals(document) && targetVersion.equals(version);
                });
        var first = new Evidence(space, UUID.randomUUID(), document, version,
                "node-1", "标题", "证据一", 0.9, new PageLocator(1, null));
        var second = new Evidence(space, UUID.randomUUID(), document, version,
                "node-2", "标题", "证据二", 0.9, new PageLocator(2, null));
        access.requireReadable(actor, space, List.of(
                GenerationEvidenceSource.from(1, first), GenerationEvidenceSource.from(2, second)), at);
        assertThat(calls).hasValue(1);
        assertThatThrownBy(() -> access.requireReadable(actor, UUID.randomUUID(),
                List.of(GenerationEvidenceSource.from(1, first)), at))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThatThrownBy(() -> access.requireReadable(actor, space, List.of(), at))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
    }
}
