package com.yunjihai.rag.qa.evaluation;

import java.time.Instant;
import java.util.*;

public interface EvaluationRepository {
    Optional<EvaluationDataset> findDataset(UUID datasetId);
    Observation observe(UUID requestId);
    void save(EvaluationRun run);
    Optional<EvaluationRun> findRun(UUID runId);

    record Observation(
            Set<EvaluationCase.EvidenceRef> retrievedTop10,
            Set<UUID> evidenceIds,
            Set<EvaluationCase.EvidenceRef> modelRequestTrace,
            Set<EvaluationCase.EvidenceRef> responseTrace) {
        public Observation {
            retrievedTop10=Set.copyOf(Objects.requireNonNull(retrievedTop10));
            evidenceIds=Set.copyOf(Objects.requireNonNull(evidenceIds));
            modelRequestTrace=Set.copyOf(Objects.requireNonNull(modelRequestTrace));
            responseTrace=Set.copyOf(Objects.requireNonNull(responseTrace));
        }
    }

    record EvaluationRun(
            UUID runId,
            UUID datasetId,
            String datasetVersion,
            String codeVersion,
            String policyVersion,
            String promptVersion,
            String sanitizationVersion,
            String modelConfigVersion,
            Instant startedAt,
            Instant completedAt,
            EvaluationMetrics metrics,
            EvaluationGate.Assessment assessment) {
        public EvaluationRun {
            Objects.requireNonNull(runId); Objects.requireNonNull(datasetId);
            Objects.requireNonNull(datasetVersion); Objects.requireNonNull(codeVersion);
            Objects.requireNonNull(policyVersion); Objects.requireNonNull(promptVersion);
            Objects.requireNonNull(sanitizationVersion); Objects.requireNonNull(modelConfigVersion);
            Objects.requireNonNull(startedAt); Objects.requireNonNull(completedAt);
            Objects.requireNonNull(metrics); Objects.requireNonNull(assessment);
            if(completedAt.isBefore(startedAt))throw new IllegalArgumentException("evaluation completion precedes start");
        }
    }
}
