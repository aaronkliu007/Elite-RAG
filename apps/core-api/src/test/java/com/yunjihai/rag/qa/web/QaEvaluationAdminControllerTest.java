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
    }

    private static QaEvaluationUseCase useCase() {
        var run=new EvaluationRepository.EvaluationRun(RUN,DATASET,"dataset","code","policy",
                "prompt","sanitizer","model",Instant.EPOCH,Instant.EPOCH,
                new EvaluationMetrics(0,0,0,0,0,0,0,0,0),new EvaluationGate.Assessment(true,List.of()));
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
