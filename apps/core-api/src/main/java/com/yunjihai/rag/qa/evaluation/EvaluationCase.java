package com.yunjihai.rag.qa.evaluation;

import java.util.*;

public record EvaluationCase(
        UUID id,
        UUID evaluationActorId,
        String question,
        Set<EvidenceRef> expectedEvidence,
        Set<UUID> forbiddenDocumentIds,
        Set<String> forbiddenNodeIds,
        boolean shouldRefuse,
        Set<String> coverageTags) {
    public EvaluationCase {
        Objects.requireNonNull(id,"case id must not be null");
        Objects.requireNonNull(evaluationActorId,"evaluation actor must not be null");
        if(question==null||question.isBlank())throw new IllegalArgumentException("evaluation question is required");
        expectedEvidence=Set.copyOf(Objects.requireNonNull(expectedEvidence));
        forbiddenDocumentIds=Set.copyOf(Objects.requireNonNull(forbiddenDocumentIds));
        forbiddenNodeIds=Set.copyOf(Objects.requireNonNull(forbiddenNodeIds));
        coverageTags=Set.copyOf(Objects.requireNonNull(coverageTags));
        if(expectedEvidence.isEmpty()&&forbiddenDocumentIds.isEmpty()&&forbiddenNodeIds.isEmpty()&&!shouldRefuse)
            throw new IllegalArgumentException("evaluation case requires expected evidence, forbidden scope, or refusal");
        if(coverageTags.isEmpty())throw new IllegalArgumentException("evaluation coverage tags are required");
    }

    public record EvidenceRef(UUID documentId,String nodeId) {
        public EvidenceRef {
            Objects.requireNonNull(documentId,"document id must not be null");
            if(nodeId==null||nodeId.isBlank())throw new IllegalArgumentException("node id is required");
        }
    }
}
