package com.yunjihai.rag.solution.application;

import java.util.UUID;

public interface SolutionCommandPort {
    SolutionCommandResult create(UUID actor, String clientKey, SolutionCommandService.CreateInput input);
    SolutionCommandResult reviseRequirements(UUID actor, String clientKey,
            SolutionCommandService.ReviseRequirementsInput input);
    SolutionCommandResult confirmRequirements(UUID actor, String clientKey, SolutionCommandService.ConfirmInput input);
    SolutionCommandResult reviseOutline(UUID actor, String clientKey,
            SolutionCommandService.ReviseOutlineInput input);
    SolutionCommandResult confirmOutline(UUID actor, String clientKey, SolutionCommandService.ConfirmInput input);
}
