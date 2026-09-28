package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.solution.domain.SolutionPhase;
import com.yunjihai.rag.solution.generation.GenerationStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SolutionWorkbenchCoreAcceptanceTest {

    @Test
    void completesTheInfrastructureNeutralSolutionWorkbenchJourney() {
        var result = new InMemorySolutionWorkbenchScenario().run();

        assertThat(result.insufficientAttemptStatus())
                .isEqualTo(GenerationStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.modelCallsAfterInsufficientEvidence()).isZero();
        assertThat(result.unsupportedAdoptionBlocked()).isTrue();
        assertThat(result.riskBlockedFinalization()).isTrue();
        assertThat(result.finalizedSnapshotCreated()).isTrue();
        assertThat(result.evidenceReauthorizationCalls()).isEqualTo(1);
        assertThat(result.finalizedSectionCount()).isEqualTo(1);
        assertThat(result.finalizedRiskConfirmationCount()).isEqualTo(1);
        assertThat(result.finalizedContentHash()).matches("[0-9a-f]{64}");
        assertThat(result.newRevisionPhase()).isEqualTo(SolutionPhase.REQUIREMENTS_DRAFT);
        assertThat(result.newSolutionRevision()).isEqualTo(2);
        assertThat(result.oldOutlineInvalidated()).isTrue();
        assertThat(result.oldSectionsInvalidated()).isTrue();
        assertThat(result.oldFinalizationInvalidated()).isTrue();
        assertThat(result.oldRiskConfirmationNoLongerMatchesCurrent()).isTrue();
        assertThat(result.persistedFinalizedSnapshotUnchanged()).isTrue();
        assertThat(result.retrievalCalls()).isEqualTo(3);
        assertThat(result.modelCalls()).isEqualTo(2);
        assertThat(result.auditCount()).isEqualTo(12);
    }
}
