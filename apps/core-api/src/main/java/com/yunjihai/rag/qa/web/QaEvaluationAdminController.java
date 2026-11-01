package com.yunjihai.rag.qa.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.access.role.*;
import com.yunjihai.rag.qa.evaluation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/admin/qa/evaluation-runs")
@Profile("local")
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${rag.qa.hmac-key:}')")
public class QaEvaluationAdminController {
    private final QaEvaluationUseCase evaluations;
    private final RoleAssignmentService roles;

    public QaEvaluationAdminController(QaEvaluationUseCase evaluations,RoleAssignmentService roles) {
        this.evaluations=Objects.requireNonNull(evaluations); this.roles=Objects.requireNonNull(roles);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EvaluationRunView start(Authentication authentication,@RequestBody StartRequest request) {
        requireSystemAdmin(authentication);
        if(request==null)throw new IllegalArgumentException("request body is required");
        return EvaluationRunView.from(evaluations.run(
                request.datasetId(),request.codeVersion(),request.policyVersion(),request.modelConfigVersion()));
    }

    @GetMapping("/{runId}")
    public EvaluationRunView get(Authentication authentication,@PathVariable UUID runId) {
        requireSystemAdmin(authentication);
        return EvaluationRunView.from(evaluations.find(runId).orElseThrow(AccessDeniedAsNotFoundException::new));
    }

    public record EvaluationRunView(UUID runId,UUID datasetId,String datasetVersion,String codeVersion,
                                    String policyVersion,String promptVersion,String sanitizationVersion,
                                    String modelConfigVersion,java.time.Instant startedAt,java.time.Instant completedAt,
                                    EvaluationMetrics metrics,EvaluationGate.Assessment assessment,
                                    EvaluationGate.Assessment historicalAssessment,String datasetDigest,List<CaseResultView> caseResults) {
        static EvaluationRunView from(EvaluationRepository.EvaluationRun run) {
            return new EvaluationRunView(run.runId(),run.datasetId(),run.datasetVersion(),run.codeVersion(),
                    run.policyVersion(),run.promptVersion(),run.sanitizationVersion(),run.modelConfigVersion(),
                    run.startedAt(),run.completedAt(),run.metrics(),run.currentAssessment(),run.assessment(),run.recordedDatasetDigest(),run.caseResults().stream().map(CaseResultView::from).toList());
        }
    }

    public record CaseResultView(UUID caseId,UUID requestId,boolean expectedRefusal,
                                 com.yunjihai.rag.qa.domain.QaStatus status,String reason,
                                 int expectedEvidence,int retrievedEvidence,int generatedClaims,
                                 int reviewedClaims,int supportedClaims,int leakageCount) {
        static CaseResultView from(EvaluationCaseResult value) {
            return new CaseResultView(value.caseId(),value.requestId(),value.expectedRefusal(),value.status(),value.reason(),
                    value.expectedEvidence(),value.retrievedEvidence(),value.generatedClaims(),value.reviewedClaims(),
                    value.supportedClaims(),value.leakageCount());
        }
    }

    private void requireSystemAdmin(Authentication authentication) {
        UUID actor=userId(authentication);
        if(!roles.hasSystemRole(actor,SystemRole.SYSTEM_ADMIN))throw new AccessDeniedAsNotFoundException();
    }

    private static UUID userId(Authentication authentication) {
        if(authentication==null||authentication.getName()==null)throw new AccessDeniedAsNotFoundException();
        try{return UUID.fromString(authentication.getName());}
        catch(IllegalArgumentException invalid){throw new AccessDeniedAsNotFoundException();}
    }

    public static final class StartRequest {
        private final UUID datasetId;
        private final String codeVersion;
        private final String policyVersion;
        private final String modelConfigVersion;

        @JsonCreator
        public StartRequest(
                @JsonProperty("datasetId") UUID datasetId,
                @JsonProperty("codeVersion") String codeVersion,
                @JsonProperty("policyVersion") String policyVersion,
                @JsonProperty("modelConfigVersion") String modelConfigVersion) {
            this.datasetId=datasetId; this.codeVersion=codeVersion;
            this.policyVersion=policyVersion; this.modelConfigVersion=modelConfigVersion;
        }
        @JsonProperty("datasetId") public UUID datasetId(){return datasetId;}
        @JsonProperty("codeVersion") public String codeVersion(){return codeVersion;}
        @JsonProperty("policyVersion") public String policyVersion(){return policyVersion;}
        @JsonProperty("modelConfigVersion") public String modelConfigVersion(){return modelConfigVersion;}
        @JsonAnySetter public void rejectUnknownField(String name,Object value) {
            throw new IllegalArgumentException("unsupported evaluation field: "+name);
        }
    }
}
