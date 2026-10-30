package com.yunjihai.rag.solution.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.solution.application.*;
import com.yunjihai.rag.solution.proposal.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@ConditionalOnBean(SolutionProposalService.class)
@RequestMapping("/api/v1/solutions/{solutionId}/proposals/{kind}")
public final class SolutionProposalController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SolutionProposalPort proposals;
    public SolutionProposalController(SolutionProposalPort proposals) { this.proposals = Objects.requireNonNull(proposals); }

    @PostMapping
    public Response generate(Authentication authentication, @PathVariable UUID solutionId, @PathVariable String kind,
            @RequestHeader("Idempotency-Key") String key, @RequestBody Request request) {
        if (request == null) throw new IllegalArgumentException("request required");
        var command = command(authentication, solutionId, key, request.expectedRevision());
        return response(switch (kind(kind)) {
            case REQUIREMENTS -> proposals.generateRequirements(command);
            case OUTLINE -> proposals.generateOutline(command);
            case DOCUMENT_REQ -> throw new IllegalArgumentException("invalid proposal kind");
        });
    }

    @GetMapping
    public Response find(Authentication authentication, @PathVariable UUID solutionId, @PathVariable String kind,
            @RequestHeader("Idempotency-Key") String key, @RequestParam Long expectedRevision) {
        return response(proposals.find(command(authentication, solutionId, key, expectedRevision), kind(kind)));
    }

    private static SolutionProposalService.Command command(Authentication authentication, UUID solutionId,
            String key, Long revision) {
        if (authentication == null || authentication.getName() == null)
            throw new AuthenticationCredentialsNotFoundException("login required");
        final UUID actor;
        try { actor = UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException invalid) { throw new AuthenticationCredentialsNotFoundException("invalid principal"); }
        if (revision == null || revision < 0) throw new IllegalArgumentException("expectedRevision required");
        return new SolutionProposalService.Command(actor, solutionId, revision, key);
    }

    private static ProposalKind kind(String value) {
        return switch (value) {
            case "requirements" -> ProposalKind.REQUIREMENTS;
            case "outline" -> ProposalKind.OUTLINE;
            default -> throw new IllegalArgumentException("invalid proposal kind");
        };
    }

    static Response response(ProposalAttempt attempt) {
        Map<String, Object> result = null;
        if (attempt.resultJson().isPresent()) {
            try { result = JSON.readValue(attempt.resultJson().orElseThrow(), new TypeReference<>() {}); }
            catch (JsonProcessingException invalid) { throw new IllegalStateException("invalid stored candidate"); }
        }
        return new Response(attempt.id(), attempt.key().kind(), attempt.status(),
                attempt.request().requirementVersionId(), attempt.request().expectedRevision(),
                result, attempt.failureCode().orElse(null));
    }

    public record Request(Long expectedRevision) { }
    public record Response(UUID id, ProposalKind kind, ProposalStatus status, UUID requirementVersionId,
            long expectedRevision, Map<String, Object> result, String failureCode) { }
}
