package com.yunjihai.rag.qa.evaluation;

import com.yunjihai.rag.common.UuidV7;
import com.yunjihai.rag.qa.*;
import com.yunjihai.rag.qa.domain.*;

import java.time.*;
import java.util.*;

public final class GroundedQaEvaluationService implements QaEvaluationUseCase {
    @FunctionalInterface public interface QaExecution { QaOutcome ask(AskQuestionCommand command); }
    private final QaExecution qa;
    private final EvaluationRepository repository;
    private final EvaluationGate gate;
    private final Clock clock;
    private final EvaluationTraceRegistry traces;

    public GroundedQaEvaluationService(
            GroundedQaService qa,EvaluationRepository repository,EvaluationGate gate,Clock clock) {
        this(qa::ask,repository,gate,clock,new EvaluationTraceRegistry());
    }

    public GroundedQaEvaluationService(
            GroundedQaService qa,EvaluationRepository repository,EvaluationGate gate,Clock clock,
            EvaluationTraceRegistry traces) {
        this(qa::ask,repository,gate,clock,traces);
    }

    GroundedQaEvaluationService(
            QaExecution qa,EvaluationRepository repository,EvaluationGate gate,Clock clock) {
        this(qa,repository,gate,clock,new EvaluationTraceRegistry());
    }

    GroundedQaEvaluationService(
            QaExecution qa,EvaluationRepository repository,EvaluationGate gate,Clock clock,
            EvaluationTraceRegistry traces) {
        this.qa=Objects.requireNonNull(qa); this.repository=Objects.requireNonNull(repository);
        this.gate=Objects.requireNonNull(gate); this.clock=Objects.requireNonNull(clock);
        this.traces=Objects.requireNonNull(traces);
    }

    @Override
    public EvaluationRepository.EvaluationRun run(
            UUID datasetId,String codeVersion,String policyVersion,String modelConfigVersion) {
        EvaluationDataset dataset=repository.findDataset(Objects.requireNonNull(datasetId))
                .orElseThrow(()->new IllegalArgumentException("EVALUATION_DATASET_NOT_FOUND"));
        if(!dataset.active())throw new IllegalStateException("EVALUATION_DATASET_NOT_ACTIVE");
        requireVersion(codeVersion); requireVersion(policyVersion); requireVersion(modelConfigVersion);
        String datasetDigest=EvaluationDatasetFingerprint.sha256(dataset);
        Instant started=clock.instant();
        Accumulator metrics=new Accumulator();
        for(EvaluationCase item:dataset.cases())evaluate(item,metrics);
        EvaluationMetrics result=metrics.metrics();
        var run=new EvaluationRepository.EvaluationRun(UuidV7.generate(started),dataset.id(),dataset.version(),
                codeVersion,policyVersion,dataset.promptVersion(),dataset.sanitizationVersion(),modelConfigVersion,
                started,clock.instant(),result,gate.assess(result),datasetDigest,datasetDigest);
        repository.save(run);
        return run;
    }

    @Override
    public Optional<EvaluationRepository.EvaluationRun> find(UUID runId) {
        return repository.findRun(Objects.requireNonNull(runId)).map(run -> run.withCurrentDatasetDigest(
                repository.findDataset(run.datasetId()).map(EvaluationDatasetFingerprint::sha256).orElse(null)));
    }

    private void evaluate(EvaluationCase item,Accumulator total) {
        UUID requestId=UuidV7.generate(clock.instant());
        traces.begin(requestId);
        final QaOutcome outcome;
        final EvaluationRepository.Observation observation;
        try {
            outcome=qa.ask(new AskQuestionCommand(requestId,item.evaluationActorId(),item.question()));
            observation=repository.observe(requestId);
        } finally {
            traces.consume(requestId);
        }
        total.expected+=item.expectedEvidence().size();
        total.retrieved+=item.expectedEvidence().stream().filter(observation.retrievedTop10()::contains).count();
        if(item.shouldRefuse()) {
            total.refusalCases++;
            if(outcome instanceof QaOutcome.Refused)total.correctRefusals++;
        } else {
            int claims=outcome instanceof QaOutcome.Answered answered?answered.claims().size():0;
            int citations=outcome instanceof QaOutcome.Answered answered?answered.claims().stream()
                    .mapToInt(claim->claim.evidenceIds().size()).sum():0;
            total.claims+=Math.max(1,claims);
            total.generatedClaims+=claims;
            total.citations+=Math.max(1,citations);
            if(outcome instanceof QaOutcome.Answered answered) {
                for(var claim:answered.claims()) {
                    var verdict=reviewed(item,claim,observation);
                    if(verdict.isPresent()) {
                        total.reviewedClaims++;
                        if(verdict.orElseThrow()==EvaluationCase.ReviewVerdict.SUPPORTED)total.supportedClaims++;
                    }
                }
                total.validCitations+=answered.claims().stream().flatMap(claim->claim.evidenceIds().stream())
                        .filter(observation.evidenceIds()::contains).count();
            }
        }
        total.leakages+=leakages(item,observation,outcome);
    }

    private static Optional<EvaluationCase.ReviewVerdict> reviewed(
            EvaluationCase item,CitationClaim claim,EvaluationRepository.Observation observation) {
        if(item.reviewQuestionDigest()==null||!item.reviewQuestionDigest().equals(observation.sanitizedQuestionDigest())
                ||claim.evidenceIds().isEmpty()||!observation.evidenceIds().containsAll(claim.evidenceIds()))return Optional.empty();
        Set<EvaluationCase.ReviewedEvidence> cited=new HashSet<>();
        for(UUID id:claim.evidenceIds()) {
            var ref=observation.evidenceReferences().get(id);
            if(ref==null)return Optional.empty();
            cited.add(ref);
        }
        return item.reviewedClaims().stream().filter(review->
                review.text().equals(claim.text())&&review.supportingEvidence().equals(cited))
                .map(EvaluationCase.ReviewedClaim::verdict).findFirst();
    }

    private static long leakages(EvaluationCase item,EvaluationRepository.Observation observation,QaOutcome outcome) {
        Set<EvaluationCase.EvidenceRef> traces=new HashSet<>(observation.retrievedTop10());
        traces.addAll(observation.modelRequestTrace()); traces.addAll(observation.responseTrace());
        long count=traces.stream().filter(ref->item.forbiddenDocumentIds().contains(ref.documentId())
                ||item.forbiddenNodeIds().contains(ref.nodeId())).count();
        if(outcome instanceof QaOutcome.Answered answered) {
            String response=answered.answer()+" "+answered.claims().stream().map(CitationClaim::text)
                    .reduce("",(left,right)->left+" "+right);
            count+=item.forbiddenDocumentIds().stream().map(UUID::toString).filter(response::contains).count();
            count+=item.forbiddenNodeIds().stream().filter(response::contains).count();
        }
        return count;
    }

    private static void requireVersion(String value) {
        if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new IllegalArgumentException("evaluation version is invalid");
    }

    private static final class Accumulator {
        int expected,citations,claims,generatedClaims,refusalCases,leakages;
        long retrieved,validCitations,supportedClaims,reviewedClaims,correctRefusals;
        EvaluationMetrics metrics() { return new EvaluationMetrics(expected,Math.toIntExact(retrieved),citations,
                Math.toIntExact(validCitations),claims,Math.toIntExact(supportedClaims),refusalCases,
                Math.toIntExact(correctRefusals),leakages,Math.toIntExact(reviewedClaims),generatedClaims); }
    }
}
