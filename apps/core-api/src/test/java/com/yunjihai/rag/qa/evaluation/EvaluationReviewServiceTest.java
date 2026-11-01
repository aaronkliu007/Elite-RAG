package com.yunjihai.rag.qa.evaluation;

import com.yunjihai.rag.qa.QaEvidenceAuthorizationPort;
import com.yunjihai.rag.qa.QaPersistencePort;
import com.yunjihai.rag.qa.domain.CitationClaim;
import com.yunjihai.rag.qa.domain.QaOutcome;
import com.yunjihai.rag.qa.domain.QaStatus;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;


class EvaluationReviewServiceTest {
    private final UUID actor=UUID.randomUUID(), other=UUID.randomUUID(), datasetId=UUID.randomUUID();
    private final UUID caseId=UUID.randomUUID(), request=UUID.randomUUID(), runId=UUID.randomUUID();
    private final UUID document=UUID.randomUUID(), version=UUID.randomUUID(), evidence=UUID.randomUUID();
    private static String sha(String text) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private final String questionDigest=sha("虚构问题");
    private final com.yunjihai.rag.qa.QuestionDigestPort hasher=text->sha("test-secret:"+text);
    private final com.yunjihai.rag.qa.sanitization.SensitiveContentSanitizer sanitizer=
            new com.yunjihai.rag.qa.sanitization.RegexSensitiveContentSanitizer(Set.of());
    private final EvaluationCase.EvidenceRef expected=new EvaluationCase.EvidenceRef(document,"node");
    private final EvaluationCase.ReviewedEvidence source=new EvaluationCase.ReviewedEvidence(document,version,"node",
            "b".repeat(64),"c".repeat(64),"d".repeat(64));
    private final EvaluationCase item=new EvaluationCase(caseId,actor,"虚构问题",Set.of(expected),Set.of(),Set.of(),false,Set.of("WORD"));
    private final EvaluationDataset dataset=new EvaluationDataset(datasetId,"v1","p1","s1",List.of(item),false);
    private final Clock clock=Clock.fixed(Instant.EPOCH,ZoneOffset.UTC);
    private EvaluationRepository.EvaluationRun saved;
    private EvaluationRepository.Observation observation;
    private boolean allowed;
    private final EvaluationRepository repo=new EvaluationRepository() {
        public Optional<EvaluationDataset> findDataset(UUID id) { return id.equals(datasetId)?Optional.of(dataset):Optional.empty(); }
        public Observation observe(UUID id) { return observation; }
        public void save(EvaluationRun value) { saved=value; }
        public Optional<EvaluationRun> findRun(UUID id) { return Optional.ofNullable(saved).filter(run->run.runId().equals(id)); }
    };
    private final QaPersistencePort persistence=(QaPersistencePort)java.lang.reflect.Proxy.newProxyInstance(
            QaPersistencePort.class.getClassLoader(),new Class<?>[]{QaPersistencePort.class},(proxy,method,args)->{
                if(method.getName().equals("find"))return args[0].equals(request)&&args[1].equals(actor)
                        ?Optional.of(new QaOutcome.Answered(request,"虚构声明",List.of(
                                new CitationClaim(UUID.randomUUID(),"虚构声明",List.of(evidence))),false)):Optional.empty();
                throw new UnsupportedOperationException(method.getName());
            });
    private final QaEvidenceAuthorizationPort authorization=(id,user,at)->allowed&&id.equals(request)&&user.equals(actor);

    private void setup(String recordedDigest,String questionHash) {
        var result=new EvaluationCaseResult(caseId,request,false,QaStatus.ANSWERED,null,1,1,1,0,0,0,questionHash==null?null:EvaluationReviewBinding.create(hasher,questionHash));
        saved=new EvaluationRepository.EvaluationRun(runId,datasetId,"v1","c","p","p1","s1","m",
                Instant.EPOCH,Instant.EPOCH,new EvaluationMetrics(1,1,1,1,1,0,0,0,0),
                new EvaluationGate.Assessment(false,List.of()),recordedDigest,null,List.of(result));
        observation=new EvaluationRepository.Observation(Set.of(expected),Set.of(evidence),Set.of(),Set.of(),Map.of(evidence,source),null);
        allowed=true;
    }

    @Test void exposesReviewedCandidateOnlyToOwnerWithCurrentAuthorization() {
        setup(EvaluationDatasetFingerprint.sha256(dataset),questionDigest);
        var service=new EvaluationReviewService(repo,persistence,authorization,clock,sanitizer,hasher);
        var review=service.find(runId,caseId,actor).orElseThrow();
        assertThat(review.question()).isEqualTo("虚构问题");
        assertThat(review.reviewQuestionDigest()).isEqualTo(questionDigest);
        assertThat(review.claims()).singleElement().satisfies(claim->{
            assertThat(claim.text()).isEqualTo("虚构声明");
            assertThat(claim.supportingEvidence()).containsExactly(source);
            assertThat(claim.eligible()).isTrue();
        });
        assertThat(service.find(runId,caseId,other)).isEmpty();
        allowed=false;
        assertThat(service.find(runId,caseId,actor)).isEmpty();
    }

    @Test void refusesOldOrChangedDatasetAndMissingQuestionDigest() {
        setup("f".repeat(64),questionDigest);
        var service=new EvaluationReviewService(repo,persistence,authorization,clock,sanitizer,hasher);
        assertThat(service.find(runId,caseId,actor)).isEmpty();
        setup(EvaluationDatasetFingerprint.sha256(dataset),null);
        assertThat(service.find(runId,caseId,actor)).isEmpty();
    }

    @Test void changedSanitizationCannotReuseOldReviewBinding() {
        setup(EvaluationDatasetFingerprint.sha256(dataset),questionDigest);
        var changed=new com.yunjihai.rag.qa.sanitization.RegexSensitiveContentSanitizer(Set.of("虚构"));
        assertThat(new EvaluationReviewService(repo,persistence,authorization,clock,changed,hasher)
                .find(runId,caseId,actor)).isEmpty();
    }

    @Test void revocationDuringReviewReadFailsClosed() {
        setup(EvaluationDatasetFingerprint.sha256(dataset),questionDigest);
        var count=new java.util.concurrent.atomic.AtomicInteger();
        QaEvidenceAuthorizationPort revoked=(id,user,at)->count.incrementAndGet()==1;
        assertThat(new EvaluationReviewService(repo,persistence,revoked,clock,sanitizer,hasher)
                .find(runId,caseId,actor)).isEmpty();
        assertThat(count.get()).isEqualTo(2);
    }

    @Test void unexpectedSourceCannotBeExportedAsReviewedEvidence() {
        setup(EvaluationDatasetFingerprint.sha256(dataset),questionDigest);
        var unexpected=new EvaluationCase.ReviewedEvidence(UUID.randomUUID(),version,"other",
                "b".repeat(64),"c".repeat(64),"d".repeat(64));
        observation=new EvaluationRepository.Observation(Set.of(),Set.of(evidence),
                Set.of(),Set.of(),Map.of(evidence,unexpected),null);
        assertThat(new EvaluationReviewService(repo,persistence,authorization,clock,sanitizer,hasher).find(runId,caseId,actor)
                .orElseThrow().claims()).singleElement().satisfies(claim->assertThat(claim.eligible()).isFalse());
    }
}
