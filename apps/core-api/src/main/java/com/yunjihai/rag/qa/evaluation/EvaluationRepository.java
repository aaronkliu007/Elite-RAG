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
            Set<EvaluationCase.EvidenceRef> responseTrace,
            Map<UUID,EvaluationCase.ReviewedEvidence> evidenceReferences,
            String sanitizedQuestionDigest) {
        public Observation(Set<EvaluationCase.EvidenceRef> retrievedTop10,Set<UUID> evidenceIds,
                           Set<EvaluationCase.EvidenceRef> modelRequestTrace,
                           Set<EvaluationCase.EvidenceRef> responseTrace) {
            this(retrievedTop10,evidenceIds,modelRequestTrace,responseTrace,Map.of(),null);
        }
        public Observation {
            retrievedTop10=Set.copyOf(Objects.requireNonNull(retrievedTop10));
            evidenceIds=Set.copyOf(Objects.requireNonNull(evidenceIds));
            modelRequestTrace=Set.copyOf(Objects.requireNonNull(modelRequestTrace));
            responseTrace=Set.copyOf(Objects.requireNonNull(responseTrace));
            evidenceReferences=Map.copyOf(Objects.requireNonNull(evidenceReferences));
            if(sanitizedQuestionDigest!=null&&!sanitizedQuestionDigest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("sanitized question digest is invalid");
            if(!evidenceIds.containsAll(evidenceReferences.keySet()))
                throw new IllegalArgumentException("evidence references must belong to observed evidence");
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
            EvaluationGate.Assessment assessment,
            String recordedDatasetDigest,
            String currentDatasetDigest,
            List<EvaluationCaseResult> caseResults) {
        public EvaluationRun(UUID runId,UUID datasetId,String datasetVersion,String codeVersion,String policyVersion,
                             String promptVersion,String sanitizationVersion,String modelConfigVersion,Instant startedAt,
                             Instant completedAt,EvaluationMetrics metrics,EvaluationGate.Assessment assessment,
                             String recordedDatasetDigest,String currentDatasetDigest) {
            this(runId,datasetId,datasetVersion,codeVersion,policyVersion,promptVersion,sanitizationVersion,
                    modelConfigVersion,startedAt,completedAt,metrics,assessment,recordedDatasetDigest,currentDatasetDigest,List.of());
        }
        public EvaluationRun(UUID runId,UUID datasetId,String datasetVersion,String codeVersion,String policyVersion,
                             String promptVersion,String sanitizationVersion,String modelConfigVersion,Instant startedAt,
                             Instant completedAt,EvaluationMetrics metrics,EvaluationGate.Assessment assessment) {
            this(runId,datasetId,datasetVersion,codeVersion,policyVersion,promptVersion,sanitizationVersion,
                    modelConfigVersion,startedAt,completedAt,metrics,assessment,null,null);
        }
        public EvaluationRun {
            Objects.requireNonNull(runId); Objects.requireNonNull(datasetId);
            Objects.requireNonNull(datasetVersion); Objects.requireNonNull(codeVersion);
            Objects.requireNonNull(policyVersion); Objects.requireNonNull(promptVersion);
            Objects.requireNonNull(sanitizationVersion); Objects.requireNonNull(modelConfigVersion);
            Objects.requireNonNull(startedAt); Objects.requireNonNull(completedAt);
            Objects.requireNonNull(metrics); Objects.requireNonNull(assessment);
            caseResults=List.copyOf(Objects.requireNonNull(caseResults));
            if(caseResults.stream().map(EvaluationCaseResult::caseId).distinct().count()!=caseResults.size())
                throw new IllegalArgumentException("duplicate evaluation case result");
            if(recordedDatasetDigest!=null&&!recordedDatasetDigest.matches("[0-9a-f]{64}") ||
                    currentDatasetDigest!=null&&!currentDatasetDigest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid dataset digest");
            if(completedAt.isBefore(startedAt))throw new IllegalArgumentException("evaluation completion precedes start");
        }
        public EvaluationGate.Assessment currentAssessment() {
            var result=new EvaluationGate().assess(metrics);
            if(recordedDatasetDigest!=null&&recordedDatasetDigest.equals(currentDatasetDigest))return result;
            var failures=new ArrayList<>(result.failures());
            failures.add(EvaluationGate.Failure.DATASET_PROVENANCE);
            return new EvaluationGate.Assessment(false,failures);
        }
        public EvaluationRun withCurrentDatasetDigest(String digest) {
            return new EvaluationRun(runId,datasetId,datasetVersion,codeVersion,policyVersion,promptVersion,
                    sanitizationVersion,modelConfigVersion,startedAt,completedAt,metrics,assessment,recordedDatasetDigest,digest,caseResults);
        }
    }
}
