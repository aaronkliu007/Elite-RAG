package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.solution.domain.Solution;
import com.yunjihai.rag.solution.domain.SolutionPhase;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record SolutionCommandResult(UUID solutionId, long revision, UUID requirementVersionId,
        Optional<UUID> outlineVersionId, SolutionPhase phase) {
    public SolutionCommandResult {
        Objects.requireNonNull(solutionId);
        if (revision < 0) throw new IllegalArgumentException("negative revision");
        Objects.requireNonNull(requirementVersionId);
        Objects.requireNonNull(outlineVersionId);
        Objects.requireNonNull(phase);
    }

    public static SolutionCommandResult from(Solution solution) {
        return new SolutionCommandResult(solution.id(), solution.revision(), solution.currentRequirementVersionId(),
                solution.currentOutlineVersionId(), solution.phase());
    }
}
