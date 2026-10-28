package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.common.UuidV7;
import com.yunjihai.rag.solution.SolutionWorkbenchService;
import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.port.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

public final class SolutionCommandService implements SolutionCommandPort {
    private final SolutionWorkbenchService workbench;
    private final SolutionRepository solutions;
    private final RequirementVersionRepository requirements;
    private final OutlineVersionRepository outlines;
    private final SolutionAccessService access;
    private final SolutionSourceAccess sources;
    private final SolutionReviewPolicy review;
    private final SolutionCommandIdempotencyStore idempotency;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SolutionCommandService(SolutionWorkbenchService workbench, SolutionRepository solutions,
            RequirementVersionRepository requirements, OutlineVersionRepository outlines,
            SolutionAccessService access, SolutionSourceAccess sources, SolutionReviewPolicy review,
            SolutionCommandIdempotencyStore idempotency, TransactionTemplate transactions, Clock clock) {
        this.workbench = Objects.requireNonNull(workbench);
        this.solutions = Objects.requireNonNull(solutions);
        this.requirements = Objects.requireNonNull(requirements);
        this.outlines = Objects.requireNonNull(outlines);
        this.access = Objects.requireNonNull(access);
        this.sources = Objects.requireNonNull(sources);
        this.review = Objects.requireNonNull(review);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    public SolutionCommandResult create(UUID actor, String clientKey, CreateInput input) {
        Objects.requireNonNull(input);
        var key = key(actor, input.spaceId(), "CREATE", clientKey);
        return execute(key, SolutionRequestDigest.create(input), input.spaceId(), null, () -> {
            var at = Instant.now(clock);
            var version = new RequirementVersion(UuidV7.generate(at), 1, input.items(), actor, at, "初始录入");
            sources.requireCurrentSources(actor, input.spaceId(), version, at);
            var solution = workbench.createSolution(new SolutionWorkbenchService.CreateSolutionCommand(
                    actor, input.spaceId(), UuidV7.generate(at), SolutionName.of(input.name()),
                    version, input.expectedRevision(), UuidV7.generate(at), at));
            return SolutionCommandResult.from(solution);
        });
    }

    public SolutionCommandResult reviseRequirements(UUID actor, String clientKey, ReviseRequirementsInput input) {
        Objects.requireNonNull(input);
        return executeForSolution(actor, clientKey, "REVISE_REQUIREMENTS", input.solutionId(),
                SolutionRequestDigest.requirements(input), () -> {
                    var current = solutions.findById(input.solutionId()).orElseThrow(AccessDeniedAsNotFoundException::new);
                    var previous = requirements.findById(current.currentRequirementVersionId())
                            .orElseThrow(() -> new SolutionLifecycleException("REQUIREMENT_VERSION_NOT_FOUND"));
                    var at = Instant.now(clock);
                    var version = new RequirementVersion(UuidV7.generate(at), previous.number() + 1,
                            input.items(), actor, at, input.changeReason());
                    sources.requireCurrentSources(actor, current.spaceId(), version, at);
                    return SolutionCommandResult.from(workbench.reviseRequirements(
                            new SolutionWorkbenchService.ReviseRequirementsCommand(actor, current.spaceId(), current.id(),
                                    version, input.expectedRevision(), UuidV7.generate(at), at)));
                });
    }

    public SolutionCommandResult confirmRequirements(UUID actor, String clientKey, ConfirmInput input) {
        Objects.requireNonNull(input);
        return executeForSolution(actor, clientKey, "CONFIRM_REQUIREMENTS", input.solutionId(),
                SolutionRequestDigest.confirm("CONFIRM_REQUIREMENTS", input), () -> {
                    var current = solutions.findById(input.solutionId()).orElseThrow(AccessDeniedAsNotFoundException::new);
                    if (!current.currentRequirementVersionId().equals(input.versionId()))
                        throw new SolutionLifecycleException("REQUIREMENT_VERSION_NOT_CURRENT");
                    var version = requirements.findById(input.versionId())
                            .orElseThrow(() -> new SolutionLifecycleException("REQUIREMENT_VERSION_NOT_FOUND"));
                    var at = Instant.now(clock);
                    if (!review.canConfirm(version.createdBy(), actor))
                        throw new SolutionLifecycleException("SELF_CONFIRMATION_FORBIDDEN");
                    sources.requireCurrentSources(actor, current.spaceId(), version, at);
                    return SolutionCommandResult.from(workbench.confirmRequirements(
                            new SolutionWorkbenchService.ConfirmRequirementsCommand(actor, current.spaceId(), current.id(),
                                    input.versionId(), input.expectedRevision(), UuidV7.generate(at), at)));
                });
    }

    public SolutionCommandResult reviseOutline(UUID actor, String clientKey, ReviseOutlineInput input) {
        Objects.requireNonNull(input);
        return executeForSolution(actor, clientKey, "REVISE_OUTLINE", input.solutionId(),
                SolutionRequestDigest.outline(input), () -> {
                    var current = solutions.findById(input.solutionId()).orElseThrow(AccessDeniedAsNotFoundException::new);
                    var at = Instant.now(clock);
                    requireRequirementSources(actor, current, at);
                    long number = outlines.nextNumber(current.id());
                    var version = new OutlineVersion(UuidV7.generate(at), number, input.nodes(), actor, at,
                            input.changeReason());
                    return SolutionCommandResult.from(workbench.reviseOutline(
                            new SolutionWorkbenchService.ReviseOutlineCommand(actor, current.spaceId(), current.id(),
                                    version, input.expectedRevision(), UuidV7.generate(at), at)));
                });
    }

    public SolutionCommandResult confirmOutline(UUID actor, String clientKey, ConfirmInput input) {
        Objects.requireNonNull(input);
        return executeForSolution(actor, clientKey, "CONFIRM_OUTLINE", input.solutionId(),
                SolutionRequestDigest.confirm("CONFIRM_OUTLINE", input), () -> {
                    var current = solutions.findById(input.solutionId()).orElseThrow(AccessDeniedAsNotFoundException::new);
                    if (current.currentOutlineVersionId().isEmpty()
                            || !current.currentOutlineVersionId().orElseThrow().equals(input.versionId()))
                        throw new SolutionLifecycleException("OUTLINE_VERSION_NOT_CURRENT");
                    var version = outlines.findById(input.versionId())
                            .orElseThrow(() -> new SolutionLifecycleException("OUTLINE_VERSION_NOT_FOUND"));
                    if (!review.canConfirm(version.createdBy(), actor))
                        throw new SolutionLifecycleException("SELF_CONFIRMATION_FORBIDDEN");
                    var at = Instant.now(clock);
                    requireRequirementSources(actor, current, at);
                    return SolutionCommandResult.from(workbench.confirmOutline(
                            new SolutionWorkbenchService.ConfirmOutlineCommand(actor, current.spaceId(), current.id(),
                                    input.versionId(), input.expectedRevision(), UuidV7.generate(at), at)));
                });
    }

    private SolutionCommandResult executeForSolution(UUID actor, String keyText, String action,
            UUID solutionId, String digest, Supplier<SolutionCommandResult> operation) {
        var current = solutions.findById(solutionId).orElseThrow(AccessDeniedAsNotFoundException::new);
        return execute(key(actor, solutionId, action, keyText), digest, current.spaceId(), solutionId, operation);
    }

    private SolutionCommandResult execute(SolutionCommandIdempotencyStore.Key key, String digest,
            UUID spaceId, UUID lockId, Supplier<SolutionCommandResult> operation) {
        return transactions.execute(status -> {
            var at = Instant.now(clock);
            access.requireView(key.actorId(), spaceId, at);
            var replay = idempotency.claim(key, digest);
            if (replay.isPresent()) {
                var result = replay.orElseThrow();
                var solution = solutions.findById(result.solutionId()).orElseThrow(AccessDeniedAsNotFoundException::new);
                if (!solution.spaceId().equals(spaceId)) throw new AccessDeniedAsNotFoundException();
                try {
                    var historical = requirements.findById(result.requirementVersionId()).orElseThrow();
                    sources.requireCurrentSources(key.actorId(), spaceId, historical, at);
                } catch (AccessDeniedAsNotFoundException | NoSuchElementException unavailable) {
                    throw new SolutionCommandConflictException("IDEMPOTENT_RESULT_UNAVAILABLE");
                }
                return result;
            }
            if (lockId != null) solutions.lockById(lockId);
            var result = operation.get();
            idempotency.complete(key, result);
            return result;
        });
    }

    private void requireRequirementSources(UUID actor, Solution solution, Instant at) {
        var version = requirements.findById(solution.currentRequirementVersionId())
                .orElseThrow(() -> new SolutionLifecycleException("REQUIREMENT_VERSION_NOT_FOUND"));
        sources.requireCurrentSources(actor, solution.spaceId(), version, at);
    }

    private static SolutionCommandIdempotencyStore.Key key(UUID actor, UUID resource, String action, String clientKey) {
        return new SolutionCommandIdempotencyStore.Key(actor, resource, action, clientKey);
    }

    public record CreateInput(UUID spaceId, String name, List<RequirementItem> items, long expectedRevision) {
        public CreateInput {
            Objects.requireNonNull(spaceId); name = SolutionName.of(name).value();
            items = List.copyOf(Objects.requireNonNull(items));
            if (expectedRevision != 0) throw new IllegalArgumentException("initial expected revision must be zero");
        }
    }

    public record ReviseRequirementsInput(UUID solutionId, List<RequirementItem> items,
            String changeReason, long expectedRevision) {
        public ReviseRequirementsInput {
            Objects.requireNonNull(solutionId); items = List.copyOf(Objects.requireNonNull(items));
            if (changeReason == null || changeReason.isBlank()) throw new IllegalArgumentException("change reason required");
            changeReason = changeReason.strip();
            if (expectedRevision < 0) throw new IllegalArgumentException("negative revision");
        }
    }

    public record ReviseOutlineInput(UUID solutionId, List<OutlineNode> nodes,
            String changeReason, long expectedRevision) {
        public ReviseOutlineInput {
            Objects.requireNonNull(solutionId); nodes = List.copyOf(Objects.requireNonNull(nodes));
            if (changeReason == null || changeReason.isBlank()) throw new IllegalArgumentException("change reason required");
            changeReason = changeReason.strip();
            if (expectedRevision < 0) throw new IllegalArgumentException("negative revision");
        }
    }

    public record ConfirmInput(UUID solutionId, UUID versionId, long expectedRevision) {
        public ConfirmInput {
            Objects.requireNonNull(solutionId); Objects.requireNonNull(versionId);
            if (expectedRevision < 0) throw new IllegalArgumentException("negative revision");
        }
    }
}
