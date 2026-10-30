package com.yunjihai.rag.qa.evaluation;

import com.yunjihai.rag.qa.domain.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroundedQaEvaluationServiceTest {
    private static final Instant NOW=Instant.parse("2026-10-27T07:00:00Z");

    @Test
    void runsEveryCaseThroughQaAndRecordsAllVersionDimensions() {
        UUID actor=UUID.randomUUID(),document=UUID.randomUUID(),evidence=UUID.randomUUID();
        var dataset=dataset(actor,document,false);
        var repository=new MemoryRepository(dataset,new EvaluationRepository.Observation(
                Set.of(new EvaluationCase.EvidenceRef(document,"node-1")),Set.of(evidence),Set.of(),Set.of()));
        var actors=new HashSet<UUID>();
        var service=new GroundedQaEvaluationService(command->{
            actors.add(command.actorId());
            return new QaOutcome.Answered(command.requestId(),"虚构回答",
                    List.of(new CitationClaim(UUID.randomUUID(),"受支持声明",List.of(evidence))),false);
        },repository,new EvaluationGate(),Clock.fixed(NOW,ZoneOffset.UTC));

        var run=service.run(dataset.id(),"code-abc","policy-v2","model-approved-v3");

        assertThat(actors).containsExactly(actor);
        assertThat(run.datasetVersion()).isEqualTo("golden-v1");
        assertThat(run.codeVersion()).isEqualTo("code-abc");
        assertThat(run.policyVersion()).isEqualTo("policy-v2");
        assertThat(run.promptVersion()).isEqualTo("prompt-v4");
        assertThat(run.sanitizationVersion()).isEqualTo("sanitizer-v5");
        assertThat(run.modelConfigVersion()).isEqualTo("model-approved-v3");
        assertThat(run.assessment().passed()).isFalse();
        assertThat(run.assessment().failures()).containsExactly(EvaluationGate.Failure.CORRECT_REFUSAL);
        assertThat(repository.saved).isEqualTo(run);
    }

    @Test
    void failsClosedWhenForbiddenScopeAppearsInModelTrace() {
        UUID actor=UUID.randomUUID(),document=UUID.randomUUID(),forbidden=UUID.randomUUID();
        var dataset=dataset(actor,document,true);
        var repository=new MemoryRepository(dataset,new EvaluationRepository.Observation(Set.of(),Set.of(),
                Set.of(new EvaluationCase.EvidenceRef(forbidden,"forbidden-node")),Set.of()));
        var service=new GroundedQaEvaluationService(command->new QaOutcome.Refused(
                command.requestId(),RefusalReason.INSUFFICIENT_EVIDENCE,false),repository,
                new EvaluationGate(),Clock.fixed(NOW,ZoneOffset.UTC));

        var run=service.run(dataset.id(),"code","policy","model");

        assertThat(run.metrics().leakageCount()).isPositive();
        assertThat(run.assessment().failures()).contains(EvaluationGate.Failure.LEAKAGE);
    }

    @Test
    void failedEvaluationReleasesTraceCapacityEvenWhenQaOrObservationThrows() {
        UUID actor=UUID.randomUUID(),document=UUID.randomUUID();
        var dataset=dataset(actor,document,false);
        var traces=new EvaluationTraceRegistry();
        var repository=new MemoryRepository(dataset,new EvaluationRepository.Observation(Set.of(),Set.of(),Set.of(),Set.of()));
        var failedQa=new GroundedQaEvaluationService(command->{ throw new IllegalStateException("qa failed"); },
                repository,new EvaluationGate(),Clock.fixed(NOW,ZoneOffset.UTC),traces);
        for(int i=0;i<101;i++) {
            assertThatThrownBy(()->failedQa.run(dataset.id(),"code","policy","model"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("qa failed");
        }
        EvaluationRepository unavailable=new EvaluationRepository() {
            public Optional<EvaluationDataset> findDataset(UUID id) { return repository.findDataset(id); }
            public Observation observe(UUID id) { throw new IllegalStateException("observation failed"); }
            public void save(EvaluationRun run) { repository.save(run); }
            public Optional<EvaluationRun> findRun(UUID id) { return repository.findRun(id); }
        };
        var failedObservation=new GroundedQaEvaluationService(command->new QaOutcome.Refused(
                command.requestId(),RefusalReason.INSUFFICIENT_EVIDENCE,false),unavailable,
                new EvaluationGate(),Clock.fixed(NOW,ZoneOffset.UTC),traces);
        for(int i=0;i<101;i++) {
            assertThatThrownBy(()->failedObservation.run(dataset.id(),"code","policy","model"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("observation failed");
        }
    }

    private static EvaluationDataset dataset(UUID actor,UUID document,boolean refusal) {
        List<EvaluationCase> cases=new ArrayList<>();
        for(int index=0;index<50;index++) {
            cases.add(new EvaluationCase(UUID.nameUUIDFromBytes(("case-"+index).getBytes()),actor,
                    "虚构评测问题 "+index,
                    refusal?Set.of():Set.of(new EvaluationCase.EvidenceRef(document,"node-1")),
                    refusal?Set.of(document):Set.of(),refusal?Set.of("forbidden-node"):Set.of(),
                    refusal,Set.of(refusal?"REFUSAL":"WORD")));
        }
        return new EvaluationDataset(UUID.randomUUID(),"golden-v1","prompt-v4","sanitizer-v5",cases,true);
    }

    private static final class MemoryRepository implements EvaluationRepository {
        private final EvaluationDataset dataset;
        private final Observation observation;
        private EvaluationRun saved;
        private MemoryRepository(EvaluationDataset dataset,Observation observation) {
            this.dataset=dataset; this.observation=observation;
        }
        public Optional<EvaluationDataset> findDataset(UUID id) { return dataset.id().equals(id)?Optional.of(dataset):Optional.empty(); }
        public Observation observe(UUID requestId) { return observation; }
        public void save(EvaluationRun run) { saved=run; }
        public Optional<EvaluationRun> findRun(UUID runId) { return Optional.ofNullable(saved).filter(run->run.runId().equals(runId)); }
    }
}
