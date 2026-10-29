package com.yunjihai.rag.solution.web;

import com.yunjihai.rag.solution.feedback.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@ConditionalOnBean(FeedbackService.class)
public final class SolutionFeedbackController {
    private final FeedbackService service;

    public SolutionFeedbackController(FeedbackService service) { this.service = service; }

    @PostMapping("/api/v1/solutions/{solutionId}/finalized-versions/{versionId}/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    public FeedbackView submit(Authentication authentication, @PathVariable UUID solutionId,
            @PathVariable UUID versionId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody FeedbackRequest request) {
        if (request == null || request.reason() == null) throw new IllegalArgumentException("reason is required");
        return FeedbackView.from(service.submit(actor(authentication), solutionId, versionId,
                request.sectionVersionId(), request.reason(), key));
    }

    @GetMapping("/api/v1/solutions/{solutionId}/finalized-versions/{versionId}/feedback/mine")
    public List<FeedbackView> mine(Authentication authentication, @PathVariable UUID solutionId,
            @PathVariable UUID versionId) {
        return service.listMine(actor(authentication), solutionId, versionId).stream()
                .map(FeedbackView::from).toList();
    }

    private static UUID actor(Authentication authentication) {
        if (authentication == null || authentication.getName() == null)
            throw new AuthenticationCredentialsNotFoundException("login required");
        try { return UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException invalid) {
            throw new AuthenticationCredentialsNotFoundException("invalid principal");
        }
    }

    public record FeedbackRequest(UUID sectionVersionId, FeedbackReason reason) { }
    public record FeedbackView(UUID id, UUID finalizedVersionId, UUID sectionVersionId,
            FeedbackReason reason, Instant createdAt) {
        static FeedbackView from(SolutionFeedback value) {
            return new FeedbackView(value.id(), value.finalizedVersionId(), value.sectionVersionId(),
                    value.reason(), value.createdAt());
        }
    }
}
