package com.yunjihai.rag.solution.web;

import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.solution.application.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@ConditionalOnBean(DocumentRequirementProposalService.class)
@RequestMapping("/api/v1/solutions/{solutionId}/document-requirement-proposals")
public final class DocumentRequirementProposalController {
    private final DocumentRequirementProposalPort proposals;
    private final DocumentRequirementProposalAdoptionPort adoption;
    private final DocumentRequirementEvidencePreviewPort evidencePreview;
    public DocumentRequirementProposalController(DocumentRequirementProposalPort proposals,
            DocumentRequirementProposalAdoptionPort adoption, DocumentRequirementEvidencePreviewPort evidencePreview) {
        this.proposals = Objects.requireNonNull(proposals); this.adoption = Objects.requireNonNull(adoption);
        this.evidencePreview = Objects.requireNonNull(evidencePreview);
    }

    @PostMapping
    public SolutionProposalController.Response generate(Authentication authentication, @PathVariable UUID solutionId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody Request request) {
        return SolutionProposalController.response(proposals.generate(command(authentication, solutionId, key, request)));
    }
    @PostMapping("/lookup")
    public SolutionProposalController.Response find(Authentication authentication, @PathVariable UUID solutionId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody Request request) {
        return SolutionProposalController.response(proposals.find(command(authentication, solutionId, key, request)));
    }
    @PostMapping("/adopt")
    public SolutionDtos.CommandResponse adopt(Authentication authentication, @PathVariable UUID solutionId,
            @RequestHeader("Proposal-Key") String proposalKey, @RequestHeader("Idempotency-Key") String adoptionKey,
            @RequestBody Request request) {
        return SolutionDtos.CommandResponse.from(adoption.adopt(command(authentication, solutionId, proposalKey, request), adoptionKey));
    }
    @PostMapping("/evidence/{evidenceId}")
    public DocumentRequirementEvidencePreviewService.Preview evidence(Authentication authentication,
            @PathVariable UUID solutionId, @PathVariable UUID evidenceId,
            @RequestHeader("Proposal-Key") String proposalKey, @RequestBody Request request) {
        return evidencePreview.preview(command(authentication, solutionId, proposalKey, request), evidenceId);
    }
    private static DocumentRequirementProposalService.Command command(Authentication authentication, UUID solutionId,
            String key, Request request) {
        if (authentication == null || authentication.getName() == null)
            throw new AuthenticationCredentialsNotFoundException("login required");
        final UUID actor;
        try { actor = UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException invalid) { throw new AuthenticationCredentialsNotFoundException("invalid principal"); }
        if (request == null || request.expectedRevision() == null || request.documents() == null)
            throw new IllegalArgumentException("request is incomplete");
        return new DocumentRequirementProposalService.Command(actor, solutionId, request.expectedRevision(), key,
                request.query(), request.documents());
    }
    public record Request(Long expectedRevision, String query, List<DocumentVersionRef> documents) { }
}
