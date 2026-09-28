package com.yunjihai.rag.solution;

import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.solution.domain.OutlineVersion;
import com.yunjihai.rag.solution.domain.OutlineNode;
import com.yunjihai.rag.solution.domain.RequirementCategory;
import com.yunjihai.rag.solution.domain.RequirementItem;
import com.yunjihai.rag.solution.domain.RequirementVersion;
import com.yunjihai.rag.solution.domain.RiskType;
import com.yunjihai.rag.solution.domain.Solution;
import com.yunjihai.rag.solution.domain.ConcurrentSolutionUpdateException;
import com.yunjihai.rag.solution.domain.SolutionLifecycleException;
import com.yunjihai.rag.solution.model.GeneratedOutlineProposal;
import com.yunjihai.rag.solution.model.GeneratedRequirementProposal;
import com.yunjihai.rag.solution.model.ModelScenario;
import com.yunjihai.rag.solution.model.PromptRef;
import com.yunjihai.rag.solution.model.SolutionModelRequest;
import com.yunjihai.rag.solution.model.SolutionModelPort;
import com.yunjihai.rag.solution.port.OutlineVersionRepository;
import com.yunjihai.rag.solution.port.RequirementVersionRepository;
import com.yunjihai.rag.solution.port.SolutionRepository;

import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class SolutionWorkbenchService {

    private final SolutionRepository solutions;
    private final RequirementVersionRepository requirements;
    private final OutlineVersionRepository outlines;
    private final SolutionAuthorizationPort authorization;
    @SuppressWarnings("unused")
    private final SolutionModelPort model;
    private final AuditSink audit;

    public SolutionWorkbenchService(
            SolutionRepository solutions,
            RequirementVersionRepository requirements,
            OutlineVersionRepository outlines,
            SolutionAuthorizationPort authorization,
            SolutionModelPort model,
            AuditSink audit) {
        this.solutions = Objects.requireNonNull(solutions, "solutions");
        this.requirements = Objects.requireNonNull(requirements, "requirements");
        this.outlines = Objects.requireNonNull(outlines, "outlines");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.model = Objects.requireNonNull(model, "model");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public Solution createSolution(CreateSolutionCommand command) {
        Objects.requireNonNull(command, "command");
        authorization.require(command.actorId(), command.spaceId(), command.solutionId(),
                SolutionAction.EDIT_REQUIREMENTS, command.at());
        var solution = Solution.create(
                command.solutionId(), command.spaceId(), command.actorId(), command.requirements(), command.at());
        requirements.insert(command.requirements());
        solutions.insert(solution);
        record(command.actorId(), command.solutionId(), AuditAction.SOLUTION_CREATE,
                command.requestId(), command.spaceId(), Map.of(
                        "requirementVersionId", command.requirements().id().toString(),
                        "versionNumber", Long.toString(command.requirements().number())));
        return solution;
    }

    public Solution reviseRequirements(ReviseRequirementsCommand command) {
        Objects.requireNonNull(command, "command");
        authorization.require(command.actorId(), command.spaceId(), command.solutionId(),
                SolutionAction.EDIT_REQUIREMENTS, command.at());
        var current = load(command.solutionId(), command.spaceId());
        var updated = current.reviseRequirements(command.requirements(), command.expectedRevision());
        requirements.insert(command.requirements());
        solutions.replace(updated, command.expectedRevision());
        record(command.actorId(), command.solutionId(), AuditAction.EDIT,
                command.requestId(), command.spaceId(), Map.of(
                        "requirementVersionId", command.requirements().id().toString(),
                        "versionNumber", Long.toString(command.requirements().number())));
        return updated;
    }

    public Solution proposeRequirements(ProposeRequirementsCommand command) {
        Objects.requireNonNull(command, "command");
        authorization.require(command.actorId(), command.spaceId(), command.solutionId(),
                SolutionAction.EDIT_REQUIREMENTS, command.at());
        var current = load(command.solutionId(), command.spaceId());
        requireRevision(current, command.expectedRevision());
        var previous = requirements.findById(current.currentRequirementVersionId())
                .orElseThrow(() -> new SolutionLifecycleException("REQUIREMENT_VERSION_NOT_FOUND"));
        var request = new SolutionModelRequest(
                command.requestId(),
                command.clientKey(),
                ModelScenario.REQUIREMENTS_EXTRACTION,
                new PromptRef("requirements", "v1"),
                previous.items(),
                Optional.empty(),
                List.of("分类必须有效", "已知事实必须包含来源"),
                List.of(),
                command.at().plusSeconds(60));
        var response = model.execute(request, GeneratedRequirementProposal.class);
        var items = response.body().items().stream()
                .map(SolutionWorkbenchService::toRequirementItem)
                .toList();
        var version = new RequirementVersion(
                command.requirementVersionId(),
                previous.number() + 1,
                items,
                command.actorId(),
                command.at(),
                command.changeReason());
        var updated = current.reviseRequirements(version, command.expectedRevision());
        requirements.insert(version);
        solutions.replace(updated, command.expectedRevision());
        record(command.actorId(), command.solutionId(), AuditAction.EDIT,
                command.requestId(), command.spaceId(), modelMetadata(
                        "requirementVersionId", version.id(), version.number(),
                        "requirements", response.modelId(), "itemCount", items.size(), response.usage()));
        return updated;
    }

    public Solution confirmRequirements(ConfirmRequirementsCommand command) {
        Objects.requireNonNull(command, "command");
        authorization.require(command.actorId(), command.spaceId(), command.solutionId(),
                SolutionAction.CONFIRM_REQUIREMENTS, command.at());
        var current = load(command.solutionId(), command.spaceId());
        if (!current.currentRequirementVersionId().equals(command.targetRequirementVersionId())) {
            throw new SolutionLifecycleException("REQUIREMENT_VERSION_NOT_CURRENT");
        }
        var updated = current.confirmRequirements(command.actorId(), command.at(), command.expectedRevision());
        solutions.replace(updated, command.expectedRevision());
        record(command.actorId(), command.solutionId(), AuditAction.REQUIREMENTS_CONFIRM,
                command.requestId(), command.spaceId(), Map.of(
                        "requirementVersionId", command.targetRequirementVersionId().toString(),
                        "versionNumber", Long.toString(current.solutionRevision())));
        return updated;
    }

    public Solution reviseOutline(ReviseOutlineCommand command) {
        Objects.requireNonNull(command, "command");
        authorization.require(command.actorId(), command.spaceId(), command.solutionId(),
                SolutionAction.CONFIRM_OUTLINE, command.at());
        var current = load(command.solutionId(), command.spaceId());
        var updated = current.reviseOutline(command.outline(), command.expectedRevision());
        outlines.insert(command.outline());
        solutions.replace(updated, command.expectedRevision());
        record(command.actorId(), command.solutionId(), AuditAction.EDIT,
                command.requestId(), command.spaceId(), Map.of(
                        "outlineVersionId", command.outline().id().toString(),
                        "versionNumber", Long.toString(command.outline().number())));
        return updated;
    }

    public Solution proposeOutline(ProposeOutlineCommand command) {
        Objects.requireNonNull(command, "command");
        authorization.require(command.actorId(), command.spaceId(), command.solutionId(),
                SolutionAction.CONFIRM_OUTLINE, command.at());
        var current = load(command.solutionId(), command.spaceId());
        requireRevision(current, command.expectedRevision());
        if (current.requirementConfirmation().isEmpty()
                || !current.requirementConfirmation().orElseThrow().versionId()
                .equals(current.currentRequirementVersionId())) {
            throw new SolutionLifecycleException("REQUIREMENTS_NOT_CONFIRMED");
        }
        var confirmed = requirements.findById(current.currentRequirementVersionId())
                .orElseThrow(() -> new SolutionLifecycleException("REQUIREMENT_VERSION_NOT_FOUND"));
        long nextNumber = current.currentOutlineVersionId()
                .map(outlineId -> outlines.findById(outlineId)
                        .orElseThrow(() -> new SolutionLifecycleException("OUTLINE_VERSION_NOT_FOUND"))
                        .number() + 1)
                .orElse(1L);
        var request = new SolutionModelRequest(
                command.requestId(),
                command.clientKey(),
                ModelScenario.OUTLINE_GENERATION,
                new PromptRef("outline", "v1"),
                confirmed.items(),
                Optional.empty(),
                List.of("至少一个必填章节", "章节顺序连续", "层级为1到4"),
                List.of(),
                command.at().plusSeconds(60));
        var response = model.execute(request, GeneratedOutlineProposal.class);
        var nodes = response.body().nodes().stream()
                .map(SolutionWorkbenchService::toOutlineNode)
                .toList();
        var version = new OutlineVersion(
                command.outlineVersionId(), nextNumber, nodes, command.actorId(), command.at());
        var updated = current.reviseOutline(version, command.expectedRevision());
        outlines.insert(version);
        solutions.replace(updated, command.expectedRevision());
        record(command.actorId(), command.solutionId(), AuditAction.EDIT,
                command.requestId(), command.spaceId(), modelMetadata(
                        "outlineVersionId", version.id(), version.number(),
                        "outline", response.modelId(), "sectionCount", nodes.size(), response.usage()));
        return updated;
    }

    public Solution confirmOutline(ConfirmOutlineCommand command) {
        Objects.requireNonNull(command, "command");
        authorization.require(command.actorId(), command.spaceId(), command.solutionId(),
                SolutionAction.CONFIRM_OUTLINE, command.at());
        var current = load(command.solutionId(), command.spaceId());
        if (current.currentOutlineVersionId().isEmpty()
                || !current.currentOutlineVersionId().orElseThrow().equals(command.targetOutlineVersionId())) {
            throw new SolutionLifecycleException("OUTLINE_VERSION_NOT_CURRENT");
        }
        var updated = current.confirmOutline(command.actorId(), command.at(), command.expectedRevision());
        solutions.replace(updated, command.expectedRevision());
        record(command.actorId(), command.solutionId(), AuditAction.OUTLINE_CONFIRM,
                command.requestId(), command.spaceId(), Map.of(
                        "outlineVersionId", command.targetOutlineVersionId().toString(),
                        "versionNumber", Long.toString(current.solutionRevision())));
        return updated;
    }

    private Solution load(UUID solutionId, UUID spaceId) {
        var solution = solutions.findById(solutionId)
                .orElseThrow(() -> new SolutionLifecycleException("SOLUTION_NOT_FOUND"));
        if (!solution.spaceId().equals(spaceId)) {
            throw new SolutionLifecycleException("SOLUTION_SPACE_MISMATCH");
        }
        return solution;
    }

    private static RequirementItem toRequirementItem(GeneratedRequirementProposal.Item item) {
        Objects.requireNonNull(item, "requirement proposal item");
        var category = RequirementCategory.valueOf(requiredToken(item.category(), "category"));
        return new RequirementItem(item.itemId(), category, item.text(), item.sources());
    }

    private static OutlineNode toOutlineNode(GeneratedOutlineProposal.Node node) {
        Objects.requireNonNull(node, "outline proposal node");
        Set<RiskType> riskTypes = node.riskTypes().stream()
                .map(value -> RiskType.valueOf(requiredToken(value, "riskType")))
                .collect(Collectors.toUnmodifiableSet());
        return new OutlineNode(
                node.sectionId(), node.title(), node.level(), node.order(), node.required(),
                node.objective(), node.retrievalHint(), riskTypes);
    }

    private static String requiredToken(String value, String field) {
        Objects.requireNonNull(value, field);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static void requireRevision(Solution solution, long expectedRevision) {
        if (solution.revision() != expectedRevision) {
            throw new ConcurrentSolutionUpdateException();
        }
    }

    private static Map<String, String> modelMetadata(
            String versionIdKey,
            UUID versionId,
            long versionNumber,
            String promptName,
            String modelId,
            String countKey,
            int count,
            com.yunjihai.rag.solution.generation.ModelUsage usage) {
        var metadata = new HashMap<String, String>();
        metadata.put(versionIdKey, versionId.toString());
        metadata.put("versionNumber", Long.toString(versionNumber));
        metadata.put("promptName", promptName);
        metadata.put("promptVersion", "v1");
        metadata.put("modelId", modelId);
        metadata.put("status", "SUCCEEDED");
        metadata.put(countKey, Integer.toString(count));
        metadata.put("durationMs", Long.toString(usage.latencyMillis()));
        metadata.put("inputTokens", Long.toString(usage.inputTokens()));
        metadata.put("outputTokens", Long.toString(usage.outputTokens()));
        metadata.put("costMicros", Long.toString(usage.costMicros()));
        return Map.copyOf(metadata);
    }

    private void record(
            UUID actorId,
            UUID solutionId,
            AuditAction action,
            UUID requestId,
            UUID spaceId,
            Map<String, String> details) {
        var metadata = new HashMap<String, String>();
        metadata.put("requestId", requestId.toString());
        metadata.put("spaceId", spaceId.toString());
        metadata.put("solutionId", solutionId.toString());
        metadata.putAll(details);
        audit.record(new AuditCommand(
                actorId,
                action,
                "SOLUTION",
                solutionId,
                AuditOutcome.SUCCEEDED,
                Map.copyOf(metadata)));
    }

    public record CreateSolutionCommand(
            UUID actorId,
            UUID spaceId,
            UUID solutionId,
            RequirementVersion requirements,
            long expectedRevision,
            UUID requestId,
            Instant at) {
        public CreateSolutionCommand {
            common(actorId, spaceId, solutionId, expectedRevision, requestId, at);
            Objects.requireNonNull(requirements, "requirements");
            if (expectedRevision != 0) {
                throw new IllegalArgumentException("initial expectedRevision must be zero");
            }
        }
    }

    public record ReviseRequirementsCommand(
            UUID actorId,
            UUID spaceId,
            UUID solutionId,
            RequirementVersion requirements,
            long expectedRevision,
            UUID requestId,
            Instant at) {
        public ReviseRequirementsCommand {
            common(actorId, spaceId, solutionId, expectedRevision, requestId, at);
            Objects.requireNonNull(requirements, "requirements");
        }
    }

    public record ProposeRequirementsCommand(
            UUID actorId,
            UUID spaceId,
            UUID solutionId,
            UUID requirementVersionId,
            String clientKey,
            String changeReason,
            long expectedRevision,
            UUID requestId,
            Instant at) {
        public ProposeRequirementsCommand {
            common(actorId, spaceId, solutionId, expectedRevision, requestId, at);
            Objects.requireNonNull(requirementVersionId, "requirementVersionId");
            clientKey = requiredToken(clientKey, "clientKey");
            changeReason = requiredToken(changeReason, "changeReason");
        }
    }

    public record ConfirmRequirementsCommand(
            UUID actorId,
            UUID spaceId,
            UUID solutionId,
            UUID targetRequirementVersionId,
            long expectedRevision,
            UUID requestId,
            Instant at) {
        public ConfirmRequirementsCommand {
            common(actorId, spaceId, solutionId, expectedRevision, requestId, at);
            Objects.requireNonNull(targetRequirementVersionId, "targetRequirementVersionId");
        }
    }

    public record ReviseOutlineCommand(
            UUID actorId,
            UUID spaceId,
            UUID solutionId,
            OutlineVersion outline,
            long expectedRevision,
            UUID requestId,
            Instant at) {
        public ReviseOutlineCommand {
            common(actorId, spaceId, solutionId, expectedRevision, requestId, at);
            Objects.requireNonNull(outline, "outline");
        }
    }

    public record ProposeOutlineCommand(
            UUID actorId,
            UUID spaceId,
            UUID solutionId,
            UUID outlineVersionId,
            String clientKey,
            long expectedRevision,
            UUID requestId,
            Instant at) {
        public ProposeOutlineCommand {
            common(actorId, spaceId, solutionId, expectedRevision, requestId, at);
            Objects.requireNonNull(outlineVersionId, "outlineVersionId");
            clientKey = requiredToken(clientKey, "clientKey");
        }
    }

    public record ConfirmOutlineCommand(
            UUID actorId,
            UUID spaceId,
            UUID solutionId,
            UUID targetOutlineVersionId,
            long expectedRevision,
            UUID requestId,
            Instant at) {
        public ConfirmOutlineCommand {
            common(actorId, spaceId, solutionId, expectedRevision, requestId, at);
            Objects.requireNonNull(targetOutlineVersionId, "targetOutlineVersionId");
        }
    }

    private static void common(
            UUID actorId,
            UUID spaceId,
            UUID solutionId,
            long expectedRevision,
            UUID requestId,
            Instant at) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(solutionId, "solutionId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(at, "at");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
    }
}
