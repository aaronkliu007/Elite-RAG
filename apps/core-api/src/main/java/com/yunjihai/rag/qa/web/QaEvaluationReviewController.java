package com.yunjihai.rag.qa.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.access.role.RoleAssignmentService;
import com.yunjihai.rag.access.role.SystemRole;
import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.qa.evaluation.EvaluationReviewService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/qa/evaluation-runs")
@Profile("local")
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${rag.qa.hmac-key:}')")
public final class QaEvaluationReviewController {
    private final EvaluationReviewService reviews;
    private final RoleAssignmentService roles;
    private final AuditSink audit;
    public QaEvaluationReviewController(EvaluationReviewService reviews,RoleAssignmentService roles,AuditSink audit) {
        this.reviews=Objects.requireNonNull(reviews); this.roles=Objects.requireNonNull(roles); this.audit=Objects.requireNonNull(audit);
    }
    @GetMapping("/{runId}/cases/{caseId}/review-context")
    public ResponseEntity<EvaluationReviewService.ReviewContext> get(
            Authentication authentication,@PathVariable UUID runId,@PathVariable UUID caseId) {
        UUID actor;
        try {
            if(authentication==null||authentication.getName()==null)throw new IllegalArgumentException();
            actor=UUID.fromString(authentication.getName());
        } catch(IllegalArgumentException invalid) { throw new AccessDeniedAsNotFoundException(); }
        if(!roles.hasSystemRole(actor,SystemRole.SYSTEM_ADMIN))throw new AccessDeniedAsNotFoundException();
        var review=reviews.find(runId,caseId,actor).orElseThrow(AccessDeniedAsNotFoundException::new);
        audit.record(new AuditCommand(actor,AuditAction.VIEW,"QA_EVALUATION_CASE",caseId,AuditOutcome.SUCCEEDED,
                Map.of("requestId",review.requestId().toString())));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(review);
    }
}
