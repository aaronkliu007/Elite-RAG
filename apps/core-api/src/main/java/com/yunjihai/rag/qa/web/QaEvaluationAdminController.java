package com.yunjihai.rag.qa.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.access.role.*;
import com.yunjihai.rag.qa.evaluation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/admin/qa/evaluation-runs")
@ConditionalOnBean({QaEvaluationUseCase.class,RoleAssignmentService.class})
public class QaEvaluationAdminController {
    private final QaEvaluationUseCase evaluations;
    private final RoleAssignmentService roles;

    public QaEvaluationAdminController(QaEvaluationUseCase evaluations,RoleAssignmentService roles) {
        this.evaluations=Objects.requireNonNull(evaluations); this.roles=Objects.requireNonNull(roles);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EvaluationRepository.EvaluationRun start(Authentication authentication,@RequestBody StartRequest request) {
        requireSystemAdmin(authentication);
        if(request==null)throw new IllegalArgumentException("request body is required");
        return evaluations.run(request.datasetId(),request.codeVersion(),request.policyVersion(),request.modelConfigVersion());
    }

    @GetMapping("/{runId}")
    public EvaluationRepository.EvaluationRun get(Authentication authentication,@PathVariable UUID runId) {
        requireSystemAdmin(authentication);
        return evaluations.find(runId).orElseThrow(AccessDeniedAsNotFoundException::new);
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
