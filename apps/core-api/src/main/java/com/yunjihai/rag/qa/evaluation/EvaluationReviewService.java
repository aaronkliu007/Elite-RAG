package com.yunjihai.rag.qa.evaluation;

import com.yunjihai.rag.qa.QuestionDigestPort;
import com.yunjihai.rag.qa.domain.QaOutcome;
import com.yunjihai.rag.qa.domain.QaQuestion;
import com.yunjihai.rag.qa.sanitization.SensitiveContentSanitizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.BiFunction;

/** Review data remains accessible only to the original actor and while all sources are authorized. */
public final class EvaluationReviewService {
    private final EvaluationRepository repository;
    private final BiFunction<UUID,UUID,Optional<QaOutcome>> safeAnswerReader;
    private final SensitiveContentSanitizer sanitizer;
    private final QuestionDigestPort hasher;
    public EvaluationReviewService(EvaluationRepository repository,BiFunction<UUID,UUID,Optional<QaOutcome>> safeAnswerReader,
            SensitiveContentSanitizer sanitizer,QuestionDigestPort hasher) {
        this.repository=Objects.requireNonNull(repository); this.safeAnswerReader=Objects.requireNonNull(safeAnswerReader);
        this.sanitizer=Objects.requireNonNull(sanitizer); this.hasher=Objects.requireNonNull(hasher);
    }
    public Optional<ReviewContext> find(UUID runId,UUID caseId,UUID actor) {
        Objects.requireNonNull(runId); Objects.requireNonNull(caseId); Objects.requireNonNull(actor);
        var run=repository.findRun(runId).orElse(null);
        if(run==null||run.recordedDatasetDigest()==null)return Optional.empty();
        var dataset=repository.findDataset(run.datasetId()).orElse(null);
        if(dataset==null||!run.recordedDatasetDigest().equals(EvaluationDatasetFingerprint.sha256(dataset)))return Optional.empty();
        var item=dataset.cases().stream().filter(candidate->candidate.id().equals(caseId)).findFirst().orElse(null);
        var result=run.caseResults().stream().filter(candidate->candidate.caseId().equals(caseId)).findFirst().orElse(null);
        if(item==null||result==null||item.shouldRefuse()||!item.evaluationActorId().equals(actor)
                ||result.reviewBinding()==null)return Optional.empty();
        final String digest;
        try {
            var safe=sanitizer.sanitizeText(QaQuestion.normalize(item.question()).value());
            if(safe.blocked())return Optional.empty();
            digest=sha256(QaQuestion.normalize(safe.value()).value());
            if(!MessageDigest.isEqual(EvaluationReviewBinding.create(hasher,digest).getBytes(StandardCharsets.US_ASCII),
                    result.reviewBinding().getBytes(StandardCharsets.US_ASCII)))return Optional.empty();
        } catch(RuntimeException failure) { return Optional.empty(); }
        var outcome=safeAnswerReader.apply(result.requestId(),actor).orElse(null);
        if(!(outcome instanceof QaOutcome.Answered answer))return Optional.empty();
        var observation=repository.observe(result.requestId());
        List<ReviewClaim> claims=new ArrayList<>();
        for(var claim:answer.claims()) {
            List<EvaluationCase.ReviewedEvidence> refs=new ArrayList<>();
            boolean eligible=true;
            for(UUID id:claim.evidenceIds()) {
                var ref=observation.evidenceReferences().get(id);
                if(ref==null) { eligible=false; continue; }
                if(!item.expectedEvidence().contains(new EvaluationCase.EvidenceRef(ref.documentId(),ref.nodeId()))
                        ||item.forbiddenDocumentIds().contains(ref.documentId())||item.forbiddenNodeIds().contains(ref.nodeId()))
                    eligible=false;
                refs.add(ref);
            }
            claims.add(new ReviewClaim(claim.claimId(),claim.text(),claim.evidenceIds(),List.copyOf(refs),eligible));
        }
        // Recheck immediately before returning sensitive question and answer content.
        if(safeAnswerReader.apply(result.requestId(),actor).filter(answer::equals).isEmpty())return Optional.empty();
        return Optional.of(new ReviewContext(caseId,result.requestId(),item.question(),digest,List.copyOf(claims)));
    }
    public record ReviewContext(UUID caseId,UUID requestId,String question,String reviewQuestionDigest,List<ReviewClaim> claims) { }
    public record ReviewClaim(UUID claimId,String text,List<UUID> evidenceIds,
                              List<EvaluationCase.ReviewedEvidence> supportingEvidence,boolean eligible) { }
    private static String sha256(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable",impossible);}
    }
}
