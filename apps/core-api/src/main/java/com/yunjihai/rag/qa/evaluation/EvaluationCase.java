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
        Set<String> coverageTags,
        Set<ReviewedClaim> reviewedClaims,
        String reviewQuestionDigest) {
    public EvaluationCase(UUID id,UUID evaluationActorId,String question,Set<EvidenceRef> expectedEvidence,
                          Set<UUID> forbiddenDocumentIds,Set<String> forbiddenNodeIds,
                          boolean shouldRefuse,Set<String> coverageTags) {
        this(id,evaluationActorId,question,expectedEvidence,forbiddenDocumentIds,forbiddenNodeIds,
                shouldRefuse,coverageTags,Set.of(),null);
    }
    public EvaluationCase {
        Objects.requireNonNull(id,"case id must not be null");
        Objects.requireNonNull(evaluationActorId,"evaluation actor must not be null");
        if(question==null||question.isBlank())throw new IllegalArgumentException("evaluation question is required");
        expectedEvidence=Set.copyOf(Objects.requireNonNull(expectedEvidence));
        forbiddenDocumentIds=Set.copyOf(Objects.requireNonNull(forbiddenDocumentIds));
        forbiddenNodeIds=Set.copyOf(Objects.requireNonNull(forbiddenNodeIds));
        coverageTags=Set.copyOf(Objects.requireNonNull(coverageTags));
        reviewedClaims=reviewedClaims==null?Set.of():Set.copyOf(reviewedClaims);
        if(!reviewedClaims.isEmpty()&&(reviewQuestionDigest==null||!reviewQuestionDigest.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("reviewed question digest is required");
        if(reviewQuestionDigest!=null&&!reviewQuestionDigest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("reviewed question digest is invalid");
        if(expectedEvidence.isEmpty()&&forbiddenDocumentIds.isEmpty()&&forbiddenNodeIds.isEmpty()&&!shouldRefuse)
            throw new IllegalArgumentException("evaluation case requires expected evidence, forbidden scope, or refusal");
        if(coverageTags.isEmpty())throw new IllegalArgumentException("evaluation coverage tags are required");
        if(shouldRefuse&&!reviewedClaims.isEmpty())throw new IllegalArgumentException("refusal case cannot approve claims");
        Set<ReviewKey> seenReviews=new HashSet<>();
        for(var reviewed:reviewedClaims) {
            if(!seenReviews.add(new ReviewKey(reviewed.text(),reviewed.supportingEvidence())))
                throw new IllegalArgumentException("duplicate reviewed claim and source combination");
            for(var ref:reviewed.supportingEvidence())
                if(!expectedEvidence.contains(new EvidenceRef(ref.documentId(),ref.nodeId()))
                        ||forbiddenDocumentIds.contains(ref.documentId())||forbiddenNodeIds.contains(ref.nodeId()))
                    throw new IllegalArgumentException("reviewed claim sources must be expected and permitted");
        }
    }

    public record EvidenceRef(UUID documentId,String nodeId) {
        public EvidenceRef {
            Objects.requireNonNull(documentId,"document id must not be null");
            if(nodeId==null||nodeId.isBlank())throw new IllegalArgumentException("node id is required");
        }
    }

    public record ReviewedEvidence(UUID documentId,UUID versionId,String nodeId,String contentDigest,String titleDigest,
                                   String locatorDigest) {
        public ReviewedEvidence {
            Objects.requireNonNull(documentId,"document id must not be null");
            Objects.requireNonNull(versionId,"version id must not be null");
            if(nodeId==null||nodeId.isBlank())throw new IllegalArgumentException("node id is required");
            if(contentDigest==null||!contentDigest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("content digest is invalid");
            if(titleDigest==null||!titleDigest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("title digest is invalid");
            if(locatorDigest==null||!locatorDigest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("locator digest is invalid");
        }
    }

    public enum ReviewVerdict { SUPPORTED, UNSUPPORTED }
    private record ReviewKey(String text,Set<ReviewedEvidence> sources) { }

    public record ReviewedClaim(String text,Set<ReviewedEvidence> supportingEvidence,String reviewReference,
                                ReviewVerdict verdict) {
        public ReviewedClaim {
            if(text==null||text.isBlank()||text.codePointCount(0,text.length())>2000)
                throw new IllegalArgumentException("reviewed claim text is invalid");
            supportingEvidence=Set.copyOf(Objects.requireNonNull(supportingEvidence));
            if(supportingEvidence.isEmpty()||supportingEvidence.size()>15)
                throw new IllegalArgumentException("reviewed claim sources are invalid");
            if(reviewReference==null||reviewReference.isBlank()||reviewReference.codePointCount(0,reviewReference.length())>128)
                throw new IllegalArgumentException("review reference is invalid");
            Objects.requireNonNull(verdict,"review verdict is required");
        }
    }
}
