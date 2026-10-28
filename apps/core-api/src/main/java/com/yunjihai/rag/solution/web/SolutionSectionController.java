package com.yunjihai.rag.solution.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.common.UuidV7;
import com.yunjihai.rag.solution.SolutionAction;
import com.yunjihai.rag.solution.SolutionAuthorizationPort;
import com.yunjihai.rag.solution.application.SolutionSectionAdoptionPort;
import com.yunjihai.rag.solution.domain.Claim;
import com.yunjihai.rag.solution.domain.SectionVersion;
import com.yunjihai.rag.solution.domain.Solution;
import com.yunjihai.rag.solution.generation.*;
import com.yunjihai.rag.solution.port.SolutionRepository;
import com.yunjihai.rag.solution.verification.SolutionRiskReport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RestController
@ConditionalOnBean(SectionGenerationService.class)
public final class SolutionSectionController {
    private final SectionGenerationPort generation;
    private final SolutionSectionAdoptionPort adoption;
    private final SolutionRepository solutions;
    private final SectionVersionRepository sections;
    private final SolutionAuthorizationPort authorization;
    private final Clock clock;

    public SolutionSectionController(SectionGenerationPort generation,
            SolutionSectionAdoptionPort adoption, SolutionRepository solutions,
            SectionVersionRepository sections,
            SolutionAuthorizationPort authorization, Clock clock) {
        this.generation = Objects.requireNonNull(generation);
        this.adoption = Objects.requireNonNull(adoption);
        this.solutions = Objects.requireNonNull(solutions);
        this.sections = Objects.requireNonNull(sections);
        this.authorization = Objects.requireNonNull(authorization);
        this.clock = Objects.requireNonNull(clock);
    }

    @PostMapping("/api/v1/solutions/{solutionId}/sections/{sectionId}/generation-attempts")
    public AttemptView generate(Authentication authentication, @PathVariable UUID solutionId,
            @PathVariable UUID sectionId, @RequestHeader("Idempotency-Key") String clientKey,
            @RequestBody GenerateRequest request) {
        if (request == null || request.expectedRevision() == null || request.expectedRevision() < 0) {
            throw new IllegalArgumentException("expectedRevision is required");
        }
        var actorId = actor(authentication);
        var current = authorized(actorId, solutionId);
        var at = clock.instant();
        var result = generation.generate(new GenerateSectionCommand(
                actorId, current.spaceId(), solutionId, sectionId,
                UuidV7.generate(at), UuidV7.generate(at), UuidV7.generate(at), clientKey,
                Optional.ofNullable(request.baselineSectionVersionId()), request.expectedRevision(), at));
        return AttemptView.from(result.attempt(), result.sectionVersion());
    }

    @GetMapping("/api/v1/solutions/{solutionId}/sections/{sectionId}/generation-attempts")
    public AttemptView getAttempt(Authentication authentication, @PathVariable UUID solutionId,
            @PathVariable UUID sectionId, @RequestParam String clientKey) {
        var actorId = actor(authentication);
        var current = authorized(actorId, solutionId);
        var result = generation.readAttempt(actorId, current.spaceId(), solutionId,
                sectionId, clientKey, clock.instant());
        return AttemptView.from(result.attempt(), result.sectionVersion());
    }

    @PostMapping("/api/v1/solutions/{solutionId}/sections/{sectionId}/versions/{sectionVersionId}/adopt")
    @ResponseStatus(HttpStatus.OK)
    public SolutionDtos.CommandResponse adopt(Authentication authentication, @PathVariable UUID solutionId,
            @PathVariable UUID sectionId, @PathVariable UUID sectionVersionId,
            @RequestHeader("Idempotency-Key") String clientKey, @RequestBody AdoptRequest request) {
        if (request == null || request.expectedRevision() == null || request.expectedRevision() < 0) {
            throw new IllegalArgumentException("expectedRevision is required");
        }
        var actorId = actor(authentication);
        return SolutionDtos.CommandResponse.from(adoption.adopt(actorId, solutionId, sectionId, sectionVersionId,
                request.expectedRevision(), clientKey));
    }

    private Solution authorized(UUID actorId, UUID solutionId) {
        var solution = solutions.findById(solutionId)
                .orElseThrow(AccessDeniedAsNotFoundException::new);
        authorization.require(actorId, solution.spaceId(), solutionId,
                SolutionAction.ADOPT_SECTION, clock.instant());
        return solution;
    }

    private static UUID actor(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AuthenticationCredentialsNotFoundException("login required");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException invalid) {
            throw new AuthenticationCredentialsNotFoundException("invalid principal");
        }
    }

    public record GenerateRequest(Long expectedRevision, UUID baselineSectionVersionId) {}
    public record AdoptRequest(Long expectedRevision) {}

    public record AttemptView(UUID id, String status, int executionCount, long revision,
            String failureCode, SectionView sectionVersion) {
        static AttemptView from(GenerationAttempt attempt, Optional<SectionVersion> version) {
            return new AttemptView(attempt.id(), attempt.status().name(), attempt.executionCount(),
                    attempt.revision(), attempt.failureCode().orElse(null),
                    version.map(SectionView::from).orElse(null));
        }
    }

    public record SectionView(UUID id, String content, List<Claim> claims,
            Set<UUID> evidenceIds, SolutionRiskReport verification) {
        static SectionView from(SectionVersion version) {
            return new SectionView(version.id(), version.content(), version.claims(),
                    version.evidenceIds(), version.verification());
        }
    }
}
