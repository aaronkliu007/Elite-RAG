package com.yunjihai.rag.solution.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.solution.SolutionAction;
import com.yunjihai.rag.solution.application.*;
import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.port.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.util.*;

@RestController
@ConditionalOnBean(SolutionCommandService.class)
public final class SolutionController {
    private final SolutionCommandPort commands;
    private final SolutionRepository solutions;
    private final SolutionQueryPort query;
    private final RequirementVersionRepository requirements;
    private final OutlineVersionRepository outlines;
    private final SolutionAccessService access;
    private final SolutionSourceAccess sources;
    private final SolutionReviewPolicy review;
    private final Clock clock;

    public SolutionController(SolutionCommandPort commands, SolutionRepository solutions, SolutionQueryPort query,
            RequirementVersionRepository requirements, OutlineVersionRepository outlines,
            SolutionAccessService access, SolutionSourceAccess sources, SolutionReviewPolicy review, Clock clock) {
        this.commands = Objects.requireNonNull(commands);
        this.solutions = Objects.requireNonNull(solutions);
        this.query = Objects.requireNonNull(query);
        this.requirements = Objects.requireNonNull(requirements);
        this.outlines = Objects.requireNonNull(outlines);
        this.access = Objects.requireNonNull(access);
        this.sources = Objects.requireNonNull(sources);
        this.review = Objects.requireNonNull(review);
        this.clock = Objects.requireNonNull(clock);
    }

    @PostMapping("/api/v1/knowledge-spaces/{spaceId}/solutions")
    @ResponseStatus(HttpStatus.CREATED)
    public SolutionDtos.CommandResponse create(Authentication authentication, @PathVariable UUID spaceId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody SolutionDtos.CreateRequest request) {
        var actor = actor(authentication);
        requireBody(request);
        var items = SolutionDtos.items(request.items(), actor, spaceId, key);
        return SolutionDtos.CommandResponse.from(commands.create(actor, key,
                new SolutionCommandService.CreateInput(spaceId, request.name(), items,
                        revision(request.expectedRevision()))));
    }

    @GetMapping("/api/v1/knowledge-spaces/{spaceId}/solutions")
    public List<SolutionDtos.Summary> list(Authentication authentication, @PathVariable UUID spaceId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        var actor = actor(authentication);
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("invalid page bounds");
        access.requireView(actor, spaceId, clock.instant());
        return query.listBySpace(spaceId, page - 1, pageSize).stream()
                .map(item -> new SolutionDtos.Summary(item.id(), item.spaceId(), item.name(), item.phase(),
                        item.revision(), item.createdAt(), item.updatedAt())).toList();
    }

    @GetMapping("/api/v1/solutions/{solutionId}")
    public SolutionDtos.Detail get(Authentication authentication, @PathVariable UUID solutionId) {
        return detail(actor(authentication), solutionId);
    }

    @PutMapping("/api/v1/solutions/{solutionId}/requirements")
    public SolutionDtos.CommandResponse reviseRequirements(Authentication authentication, @PathVariable UUID solutionId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody SolutionDtos.ReviseRequirementsRequest request) {
        var actor = actor(authentication);
        requireBody(request);
        return SolutionDtos.CommandResponse.from(commands.reviseRequirements(actor, key,
                new SolutionCommandService.ReviseRequirementsInput(solutionId,
                        SolutionDtos.items(request.items(), actor, solutionId, key),
                        request.changeReason(), revision(request.expectedRevision()))));
    }

    @PostMapping("/api/v1/solutions/{solutionId}/requirements/{versionId}/confirm")
    public SolutionDtos.CommandResponse confirmRequirements(Authentication authentication, @PathVariable UUID solutionId,
            @PathVariable UUID versionId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody SolutionDtos.ConfirmRequest request) {
        requireBody(request);
        return SolutionDtos.CommandResponse.from(commands.confirmRequirements(actor(authentication), key,
                new SolutionCommandService.ConfirmInput(solutionId, versionId, revision(request.expectedRevision()))));
    }

    @PutMapping("/api/v1/solutions/{solutionId}/outline")
    public SolutionDtos.CommandResponse reviseOutline(Authentication authentication, @PathVariable UUID solutionId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody SolutionDtos.ReviseOutlineRequest request) {
        var actor = actor(authentication);
        requireBody(request);
        return SolutionDtos.CommandResponse.from(commands.reviseOutline(actor, key,
                new SolutionCommandService.ReviseOutlineInput(solutionId,
                        SolutionDtos.nodes(request.nodes(), actor, solutionId, key),
                        revision(request.expectedRevision()))));
    }

    @PostMapping("/api/v1/solutions/{solutionId}/outline/{versionId}/confirm")
    public SolutionDtos.CommandResponse confirmOutline(Authentication authentication, @PathVariable UUID solutionId,
            @PathVariable UUID versionId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody SolutionDtos.ConfirmRequest request) {
        requireBody(request);
        return SolutionDtos.CommandResponse.from(commands.confirmOutline(actor(authentication), key,
                new SolutionCommandService.ConfirmInput(solutionId, versionId, revision(request.expectedRevision()))));
    }

    private SolutionDtos.Detail detail(UUID actor, UUID solutionId) {
        var solution = solutions.findById(solutionId).orElseThrow(AccessDeniedAsNotFoundException::new);
        var at = clock.instant();
        access.requireView(actor, solution.spaceId(), at);
        var requirement = requirements.findById(solution.currentRequirementVersionId())
                .orElseThrow(AccessDeniedAsNotFoundException::new);
        boolean sourcesValid;
        try {
            sources.requireCurrentSources(actor, solution.spaceId(), requirement, at);
            sourcesValid = true;
        } catch (AccessDeniedAsNotFoundException revoked) {
            sourcesValid = false;
        }
        final boolean currentSourcesValid = sourcesValid;
        var outline = solution.currentOutlineVersionId().flatMap(outlines::findById).orElse(null);
        var allowed = EnumSet.noneOf(SolutionAction.class);
        allowed.addAll(access.allowedActions(actor, solution.spaceId(), at));
        if (solution.phase() == SolutionPhase.FINALIZED) allowed.clear();
        if (solution.phase() != SolutionPhase.REQUIREMENTS_DRAFT || !sourcesValid
                || !review.canConfirm(requirement.createdBy(), actor))
            allowed.remove(SolutionAction.CONFIRM_REQUIREMENTS);
        if (solution.requirementConfirmation().isEmpty() || !sourcesValid)
            allowed.remove(SolutionAction.EDIT_OUTLINE);
        if (solution.phase() != SolutionPhase.OUTLINE_DRAFT || outline == null || !sourcesValid
                || !review.canConfirm(outline.createdBy(), actor))
            allowed.remove(SolutionAction.CONFIRM_OUTLINE);
        return new SolutionDtos.Detail(solution.id(), solution.spaceId(), solution.name().value(),
                solution.phase(), solution.revision(), solution.solutionRevision(), solution.createdAt(),
                sourcesValid ? SolutionDtos.RequirementVersionView.from(requirement) : null,
                sourcesValid && outline != null ? SolutionDtos.OutlineVersionView.from(outline) : null,
                solution.requirementConfirmation().map(c -> SolutionDtos.ConfirmationView.from(c, currentSourcesValid)).orElse(null),
                solution.outlineConfirmation().map(c -> SolutionDtos.ConfirmationView.from(c, currentSourcesValid)).orElse(null),
                sourcesValid, allowed.stream().map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private static UUID actor(Authentication authentication) {
        if (authentication == null || authentication.getName() == null)
            throw new AuthenticationCredentialsNotFoundException("login required");
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException invalid) {
            throw new AuthenticationCredentialsNotFoundException("invalid principal");
        }
    }

    private static long revision(Long value) {
        if (value == null || value < 0) throw new IllegalArgumentException("expectedRevision is required");
        return value;
    }

    private static <T> T requireBody(T value) {
        if (value == null) throw new IllegalArgumentException("request body is required");
        return value;
    }
}
