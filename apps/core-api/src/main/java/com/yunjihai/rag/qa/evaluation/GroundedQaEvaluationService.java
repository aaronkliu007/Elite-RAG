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
        Instant started=clock.instant();
        Accumulator metrics=new Accumulator();
        for(EvaluationCase item:dataset.cases())evaluate(item,metrics);
        EvaluationMetrics result=metrics.metrics();
        var run=new EvaluationRepository.EvaluationRun(UuidV7.generate(started),dataset.id(),dataset.version(),
                codeVersion,policyVersion,dataset.promptVersion(),dataset.sanitizationVersion(),modelConfigVersion,
                started,clock.instant(),result,gate.assess(result));
        repository.save(run);
        return run;
    }

    @Override
    public Optional<EvaluationRepository.EvaluationRun> find(UUID runId) {
        return repository.findRun(Objects.requireNonNull(runId));
    }

    private void evaluate(EvaluationCase item,Accumulator total) {
        UUID requestId=UuidV7.generate(clock.instant());
        traces.begin(requestId);
        QaOutcome outcome=qa.ask(new AskQuestionCommand(requestId,item.evaluationActorId(),item.question()));
        EvaluationRepository.Observation observation=repository.observe(requestId);
        traces.consume(requestId);
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
            total.citations+=Math.max(1,citations);
            if(outcome instanceof QaOutcome.Answered answered) {
                total.supportedClaims+=answered.claims().stream()
                        .filter(claim->!claim.text().isBlank()&&!claim.evidenceIds().isEmpty()
                                &&observation.evidenceIds().containsAll(claim.evidenceIds()))
                        .count();
                total.validCitations+=answered.claims().stream().flatMap(claim->claim.evidenceIds().stream())
                        .filter(observation.evidenceIds()::contains).count();
            }
        }
        total.leakages+=leakages(item,observation,outcome);
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
        int expected,citations,claims,refusalCases,leakages;
        long retrieved,validCitations,supportedClaims,correctRefusals;
        EvaluationMetrics metrics() { return new EvaluationMetrics(expected,Math.toIntExact(retrieved),citations,
                Math.toIntExact(validCitations),claims,Math.toIntExact(supportedClaims),refusalCases,
                Math.toIntExact(correctRefusals),leakages); }
    }
}
