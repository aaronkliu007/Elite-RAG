package com.yunjihai.rag.qa.evaluation;

import java.util.Optional;
import java.util.UUID;

public interface QaEvaluationUseCase {
    EvaluationRepository.EvaluationRun run(
            UUID datasetId,String codeVersion,String policyVersion,String modelConfigVersion);
    Optional<EvaluationRepository.EvaluationRun> find(UUID runId);
}
