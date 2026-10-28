package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.solution.SolutionAction;
import com.yunjihai.rag.solution.SolutionAuthorizationPort;
import com.yunjihai.rag.solution.port.RequirementVersionRepository;
import com.yunjihai.rag.solution.port.SolutionRepository;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Rechecks both the space role and every current requirement source before generation or adoption. */
public final class SolutionGenerationAuthorizationService implements SolutionAuthorizationPort {
    private final SolutionAccessService access;
    private final SolutionSourceAccess sources;
    private final SolutionRepository solutions;
    private final RequirementVersionRepository requirements;

    public SolutionGenerationAuthorizationService(SolutionAccessService access,
            SolutionSourceAccess sources, SolutionRepository solutions,
            RequirementVersionRepository requirements) {
        this.access = Objects.requireNonNull(access);
        this.sources = Objects.requireNonNull(sources);
        this.solutions = Objects.requireNonNull(solutions);
        this.requirements = Objects.requireNonNull(requirements);
    }

    @Override
    public void require(UUID actorId, UUID spaceId, UUID solutionId, SolutionAction action, Instant at) {
        access.requireAction(actorId, spaceId, solutionId, action, at);
        var solution = solutions.findById(solutionId)
                .orElseThrow(AccessDeniedAsNotFoundException::new);
        if (!solution.spaceId().equals(spaceId)) {
            throw new AccessDeniedAsNotFoundException();
        }
        var version = requirements.findById(solution.currentRequirementVersionId())
                .orElseThrow(AccessDeniedAsNotFoundException::new);
        sources.requireCurrentSources(actorId, spaceId, version, at);
    }
}
