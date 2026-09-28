package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.retrieval.domain.DegradationReason;
import com.yunjihai.rag.retrieval.search.RetrievalUnavailableException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustedRetrievalAcceptanceTest {

    @Test
    void provesTrustedRetrievalBehaviorWithOnlyInMemoryPorts() {
        try (var scenario = new InMemoryTrustedRetrievalScenario()) {
            var first = scenario.retrieveHealthy();
            var repeated = scenario.retrieveHealthy();

            assertThat(first).isEqualTo(repeated);
            assertThat(first.evidence()).isNotEmpty();
            assertThat(first.evidence())
                    .extracting(evidence -> evidence.documentId())
                    .doesNotContain(scenario.revokedDocumentId(), scenario.replacedDocumentId());
            assertThat(first.evidence().stream()
                    .filter(evidence -> scenario.nearDuplicateDocumentIds()
                            .contains(evidence.documentId())))
                    .hasSize(1);
            assertThat(first.evidence().stream()
                    .filter(evidence -> evidence.documentId().equals(scenario.cappedDocumentId())))
                    .hasSizeLessThanOrEqualTo(3);
            assertThat(first.evidence()).allSatisfy(evidence -> {
                assertThat(evidence.evidenceId()).isNotNull();
                assertThat(evidence.locator()).isNotNull();
            });

            var degraded = scenario.retrieveWithVectorFailure();
            assertThat(degraded.evidence()).isNotEmpty();
            assertThat(degraded.degraded()).isTrue();
            assertThat(degraded.degradationReasons())
                    .containsExactly(DegradationReason.VECTOR_UNAVAILABLE);

            assertThatThrownBy(scenario::retrieveWithBothRecallBranchesFailed)
                    .isInstanceOf(RetrievalUnavailableException.class);
        }
    }
}
