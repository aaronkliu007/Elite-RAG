package com.yunjihai.rag.qa.web;

import com.yunjihai.rag.access.*;
import com.yunjihai.rag.access.role.*;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import com.yunjihai.rag.qa.evaluation.*;
import com.yunjihai.rag.retrieval.projection.ProjectionChangePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QaEvaluationAdminControllerTest {
    private static final UUID ADMIN=UUID.randomUUID(),ORDINARY=UUID.randomUUID(),RUN=UUID.randomUUID(),DATASET=UUID.randomUUID();

    @Test
    void nonAdministratorsAreHiddenAndRequestCannotOverrideIdentityOrGates() {
        var controller=new QaEvaluationAdminController(useCase(),roles(false));
        var request=new QaEvaluationAdminController.StartRequest(DATASET,"code","policy","model");

        assertThatThrownBy(()->controller.start(auth(ORDINARY),request))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThatThrownBy(()->controller.get(auth(ORDINARY),RUN))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThatThrownBy(()->new ObjectMapper().readValue("""
                {"datasetId":"%s","codeVersion":"code","policyVersion":"policy",
                 "modelConfigVersion":"model","evaluationUserId":"%s","skipAuthorization":true}
                """.formatted(DATASET,ORDINARY),QaEvaluationAdminController.StartRequest.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause().hasMessageContaining("unsupported evaluation field");
    }

    @Test
    void systemAdministratorStartsAndReadsRuns() {
        var controller=new QaEvaluationAdminController(useCase(),roles(true));
        var started=controller.start(auth(ADMIN),new QaEvaluationAdminController.StartRequest(
                DATASET,"code","policy","model"));
        assertThat(started.runId()).isEqualTo(RUN);
        assertThat(controller.get(auth(ADMIN),RUN)).isEqualTo(started);
        var json=new ObjectMapper().valueToTree(started);
        assertThat(json.path("assessment").path("passed").asBoolean()).isFalse();
        assertThat(json.path("historicalAssessment").path("passed").asBoolean()).isTrue();
        assertThat(json.path("caseResults").size()).isEqualTo(1);
        assertThat(json.path("caseResults").get(0).path("reason").asString()).isEqualTo("CONFLICTING_EVIDENCE");
        assertThat(json.toString()).doesNotContain("question", "answer", "excerpt", "title");
        assertThat(json.toString()).doesNotContain("reviewBinding");
    }

    @Test
    void reviewContextRequiresAdminOwnershipAndCurrentSourceAuthorizationAndAuditsAccess() {
        UUID caseId=UUID.randomUUID(),request=UUID.randomUUID(),doc=UUID.randomUUID(),version=UUID.randomUUID(),evidence=UUID.randomUUID();
        var ref=new EvaluationCase.EvidenceRef(doc,"node");
        var source=new EvaluationCase.ReviewedEvidence(doc,version,"node","a".repeat(64),"b".repeat(64),"c".repeat(64));
        var item=new EvaluationCase(caseId,ADMIN,"虚构问题",Set.of(ref),Set.of(),Set.of(),false,Set.of("WORD"));
        var dataset=new EvaluationDataset(DATASET,"v1","p1","s1",List.of(item),false);
        var result=new EvaluationCaseResult(caseId,request,false,com.yunjihai.rag.qa.domain.QaStatus.ANSWERED,
                null,1,1,1,0,0,0,"d".repeat(64));
        var run=new EvaluationRepository.EvaluationRun(RUN,DATASET,"v1","c","p","p1","s1","m",
                Instant.EPOCH,Instant.EPOCH,new EvaluationMetrics(1,1,1,1,1,0,0,0,0),
                new EvaluationGate.Assessment(false,List.of()),EvaluationDatasetFingerprint.sha256(dataset),null,List.of(result));
        EvaluationRepository repo=new EvaluationRepository() {
            public Optional<EvaluationDataset> findDataset(UUID id) { return DATASET.equals(id)?Optional.of(dataset):Optional.empty(); }
            public Observation observe(UUID id) { return new Observation(Set.of(ref),Set.of(evidence),Set.of(),Set.of(),Map.of(evidence,source),null); }
            public void save(EvaluationRun r) { }
            public Optional<EvaluationRun> findRun(UUID id) { return RUN.equals(id)?Optional.of(run):Optional.empty(); }
        };
        var persistence=(com.yunjihai.rag.qa.QaPersistencePort)java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class<?>[]{com.yunjihai.rag.qa.QaPersistencePort.class},(proxy,method,args)->{
                    if(method.getName().equals("find"))return Optional.of(new com.yunjihai.rag.qa.domain.QaOutcome.Answered(request,"虚构声明",
                            List.of(new com.yunjihai.rag.qa.domain.CitationClaim(UUID.randomUUID(),"虚构声明",List.of(evidence))),false));
                    throw new UnsupportedOperationException();
                });
        boolean[] allowed={true};
        var service=new EvaluationReviewService(repo,persistence,(id,actor,at)->allowed[0],
                java.time.Clock.fixed(Instant.EPOCH,java.time.ZoneOffset.UTC),
                new com.yunjihai.rag.qa.sanitization.RegexSensitiveContentSanitizer(Set.of()),value->"d".repeat(64));
        List<com.yunjihai.rag.audit.AuditCommand> events=new ArrayList<>();
        var controller=new QaEvaluationReviewController(service,roles(true),command->{ events.add(command); return null; });
        var response=controller.get(auth(ADMIN),RUN,caseId);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getBody().question()).isEqualTo("虚构问题");
        assertThat(events).singleElement().satisfies(event->assertThat(event.action()).isEqualTo(com.yunjihai.rag.audit.AuditAction.VIEW));
        assertThatThrownBy(()->controller.get(auth(ORDINARY),RUN,caseId)).isInstanceOf(AccessDeniedAsNotFoundException.class);
        allowed[0]=false;
        assertThatThrownBy(()->controller.get(auth(ADMIN),RUN,caseId)).isInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThat(events).hasSize(1);
    }

    private static QaEvaluationUseCase useCase() {
        var run=new EvaluationRepository.EvaluationRun(RUN,DATASET,"dataset","code","policy",
                "prompt","sanitizer","model",Instant.EPOCH,Instant.EPOCH,
                new EvaluationMetrics(0,0,0,0,0,0,0,0,0),new EvaluationGate.Assessment(true,List.of()),
                null,null,List.of(new EvaluationCaseResult(UUID.randomUUID(),UUID.randomUUID(),false,
                        com.yunjihai.rag.qa.domain.QaStatus.REFUSED,"CONFLICTING_EVIDENCE",2,1,0,0,0,0)));
        return new QaEvaluationUseCase() {
            public EvaluationRepository.EvaluationRun run(UUID datasetId,String code,String policy,String model) { return run; }
            public Optional<EvaluationRepository.EvaluationRun> find(UUID runId) { return RUN.equals(runId)?Optional.of(run):Optional.empty(); }
        };
    }

    private static UsernamePasswordAuthenticationToken auth(UUID id) {
        return new UsernamePasswordAuthenticationToken(id.toString(),"n/a",List.of());
    }

    private static RoleAssignmentService roles(boolean admin) {
        RoleAssignmentStore store=new RoleAssignmentStore() {
            public boolean hasSystemRole(UUID user,SystemRole role) { return admin&&ADMIN.equals(user); }
            public boolean hasAnySystemRole(SystemRole role) { return admin; }
            public Set<SpaceRole> activeSpaceRoles(UUID user,UUID space) { return Set.of(); }
            public void grantSystemRole(UUID user,SystemRole role,UUID actor,Instant at) { }
            public void grantSpaceRole(UUID space,UUID user,SpaceRole role,UUID actor,Instant at) { }
            public void revokeSpaceRole(UUID space,UUID user,SpaceRole role,UUID actor,Instant at) { }
        };
        AclCommandRepository acl=new AclCommandRepository() {
            public boolean isWithinSpaceScope(UUID s,PrincipalRef p,Instant at) { return false; }
            public void grantSpace(UUID s,PrincipalRef p,Instant until,Instant at) { }
            public void grantDocument(UUID s,UUID d,PrincipalRef p,Instant until,Instant at) { }
            public void revokeSpace(UUID s,PrincipalRef p,Instant at) { }
            public void revokeDocument(UUID s,UUID d,PrincipalRef p,Instant at) { }
        };
        return new RoleAssignmentService(store,acl,command->null,ProjectionChangePublisher.noop(),KnowledgeWriteAuthorization.allowAll());
    }
}
