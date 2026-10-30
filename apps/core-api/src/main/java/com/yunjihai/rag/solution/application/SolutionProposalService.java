package com.yunjihai.rag.solution.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import com.yunjihai.rag.infrastructure.postgres.SolutionLocatorJson;
import com.yunjihai.rag.solution.SolutionAction;
import com.yunjihai.rag.solution.SolutionAuthorizationPort;
import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.model.*;
import com.yunjihai.rag.solution.port.RequirementVersionRepository;
import com.yunjihai.rag.solution.port.SolutionRepository;
import com.yunjihai.rag.solution.proposal.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Produces read-only model candidates; adoption uses the existing revision commands. */
public final class SolutionProposalService implements SolutionProposalPort {
    private static final Duration LEASE = Duration.ofSeconds(90);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SolutionRepository solutions;
    private final RequirementVersionRepository requirements;
    private final SolutionAuthorizationPort authorization;
    private final SolutionSourceAccess sources;
    private final ProposalAttemptStore attempts;
    private final SolutionModelPort model;
    private final Clock clock;
    private final boolean modelEnabled;

    public SolutionProposalService(SolutionRepository solutions, RequirementVersionRepository requirements,
            SolutionAuthorizationPort authorization, SolutionSourceAccess sources,
            ProposalAttemptStore attempts, SolutionModelPort model, Clock clock, boolean modelEnabled) {
        this.solutions = Objects.requireNonNull(solutions);
        this.requirements = Objects.requireNonNull(requirements);
        this.authorization = Objects.requireNonNull(authorization);
        this.sources = Objects.requireNonNull(sources);
        this.attempts = Objects.requireNonNull(attempts);
        this.model = Objects.requireNonNull(model);
        this.clock = Objects.requireNonNull(clock);
        this.modelEnabled = modelEnabled;
    }

    public ProposalAttempt generateRequirements(Command command) {
        return generate(command, ProposalKind.REQUIREMENTS);
    }

    public ProposalAttempt generateOutline(Command command) {
        return generate(command, ProposalKind.OUTLINE);
    }

    public ProposalAttempt find(Command command, ProposalKind kind) {
        var context = context(command, kind);
        var key = new ProposalKey(command.actorId(), command.solutionId(), kind, command.clientKey());
        var request = new ProposalRegistrationRequest(key, context.version().id(), command.expectedRevision(),
                SolutionRequestDigest.proposal(kind, command.solutionId(), command.expectedRevision(), context.version()));
        return attempts.findCurrent(request)
                .orElseThrow(AccessDeniedAsNotFoundException::new);
    }

    private ProposalAttempt generate(Command command, ProposalKind kind) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new SolutionLifecycleException("MODEL_PROPOSAL_TRANSACTION_FORBIDDEN");
        if (!modelEnabled) throw new SolutionLifecycleException("MODEL_PROPOSAL_UNAVAILABLE");
        var context = context(command, kind);
        var key = new ProposalKey(command.actorId(), command.solutionId(), kind, command.clientKey());
        var request = new ProposalRegistrationRequest(key, context.version().id(), command.expectedRevision(),
                SolutionRequestDigest.proposal(kind, command.solutionId(), command.expectedRevision(), context.version()));
        var registration = attempts.register(request, LEASE);
        if (!registration.created()) return registration.attempt();

        var modelRequest = new SolutionModelRequest(registration.attempt().id(), command.clientKey(),
                kind == ProposalKind.REQUIREMENTS ? ModelScenario.REQUIREMENTS_EXTRACTION
                        : ModelScenario.OUTLINE_GENERATION,
                new PromptRef(kind == ProposalKind.REQUIREMENTS ? "requirements" : "outline", "v1"),
                context.version().items(), Optional.empty(),
                kind == ProposalKind.REQUIREMENTS
                        ? List.of("分类必须有效", "已知事实必须包含来源")
                        : List.of("至少一个必填章节", "章节顺序连续", "层级为1到4"),
                List.of(), Instant.now(clock).plusSeconds(60));
        final String result;
        try {
            result = kind == ProposalKind.REQUIREMENTS
                    ? requirementsJson(model.execute(modelRequest, GeneratedRequirementProposal.class).body(),
                            context.version(), command.expectedRevision())
                    : outlineJson(model.execute(modelRequest, GeneratedOutlineProposal.class).body(),
                            context.version(), command.expectedRevision());
        } catch (UnsupportedOperationException disabled) {
            attempts.fail(request, "MODEL_DISABLED");
            throw new SolutionLifecycleException("MODEL_PROPOSAL_UNAVAILABLE");
        } catch (IllegalArgumentException | NullPointerException invalid) {
            attempts.fail(request, "MODEL_OUTPUT_INVALID");
            throw new SolutionLifecycleException("MODEL_PROPOSAL_INVALID");
        } catch (ModelGatewayException gateway) {
            if (gateway.kind() == ModelGatewayException.Kind.TIMEOUT
                    || gateway.kind() == ModelGatewayException.Kind.UNAVAILABLE) {
                throw new SolutionLifecycleException("MODEL_PROPOSAL_UNCERTAIN");
            }
            attempts.fail(request, "MODEL_GATEWAY_" + gateway.kind().name());
            throw new SolutionLifecycleException(gateway.kind() == ModelGatewayException.Kind.INVALID_RESPONSE
                    ? "MODEL_PROPOSAL_INVALID" : "MODEL_PROPOSAL_REJECTED");
        } catch (RuntimeException uncertain) {
            // The provider may have charged for this request. Let the lease expire to INDETERMINATE.
            throw new SolutionLifecycleException("MODEL_PROPOSAL_UNCERTAIN");
        }

        try {
            var current = context(command, kind);
            if (!current.version().id().equals(request.requirementVersionId())
                    || !SolutionRequestDigest.proposal(kind, command.solutionId(), command.expectedRevision(),
                            current.version()).equals(request.inputFingerprint())) {
                attempts.fail(request, "STALE_CONTEXT");
                throw new SolutionLifecycleException("MODEL_PROPOSAL_STALE");
            }
        } catch (RuntimeException changed) {
            attempts.fail(request, "CONTEXT_UNAVAILABLE");
            throw changed;
        }
        if (!attempts.succeed(request, result)) {
            throw new SolutionLifecycleException("MODEL_PROPOSAL_LEASE_EXPIRED");
        }
        return attempts.findByKey(key).orElseThrow(() -> new SolutionLifecycleException("MODEL_PROPOSAL_NOT_FOUND"));
    }

    private Context context(Command command, ProposalKind kind) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(kind);
        var solution = solutions.findById(command.solutionId())
                .orElseThrow(AccessDeniedAsNotFoundException::new);
        var at = Instant.now(clock);
        authorization.require(command.actorId(), solution.spaceId(), command.solutionId(),
                kind == ProposalKind.REQUIREMENTS ? SolutionAction.EDIT_REQUIREMENTS : SolutionAction.EDIT_OUTLINE, at);
        if (solution.revision() != command.expectedRevision())
            throw new ConcurrentSolutionUpdateException();
        if (kind == ProposalKind.OUTLINE && (solution.requirementConfirmation().isEmpty()
                || !solution.requirementConfirmation().orElseThrow().versionId()
                        .equals(solution.currentRequirementVersionId())))
            throw new SolutionLifecycleException("REQUIREMENTS_NOT_CONFIRMED");
        var version = requirements.findById(solution.currentRequirementVersionId())
                .orElseThrow(() -> new SolutionLifecycleException("REQUIREMENT_VERSION_NOT_FOUND"));
        sources.requireCurrentSources(command.actorId(), solution.spaceId(), version, at);
        return new Context(version);
    }

    private static String requirementsJson(GeneratedRequirementProposal proposal, RequirementVersion source,
            long expectedRevision) {
        var allowed = source.items().stream().flatMap(item -> item.sources().stream())
                .collect(Collectors.toUnmodifiableSet());
        return requirementsJson(proposal, source, expectedRevision, allowed);
    }

    static String requirementsJson(GeneratedRequirementProposal proposal, RequirementVersion source,
            long expectedRevision, Set<RequirementSource> allowed) {
        var items = Objects.requireNonNull(proposal).items().stream().map(item -> {
            var parsed = new RequirementItem(item.itemId(), RequirementCategory.valueOf(item.category()),
                    item.text(), item.sources());
            if (!allowed.containsAll(parsed.sources())) throw new IllegalArgumentException("forged source");
            return parsed;
        }).toList();
        new RequirementVersion(UUID.randomUUID(), source.number() + 1, items, source.createdBy(),
                source.createdAt(), "模型候选");
        var views = items.stream().map(item -> Map.of("itemId", item.itemId(), "category", item.category().name(),
                "text", item.text(), "sources", item.sources().stream().map(SolutionProposalService::sourceView).toList()))
                .toList();
        return json(Map.of("kind", "REQUIREMENTS", "requirementVersionId", source.id(),
                "expectedRevision", expectedRevision, "items", views));
    }

    private static String outlineJson(GeneratedOutlineProposal proposal, RequirementVersion source,
            long expectedRevision) {
        var nodes = Objects.requireNonNull(proposal).nodes().stream().map(node -> new OutlineNode(
                node.sectionId(), node.title(), node.level(), node.order(), node.required(), node.objective(),
                node.retrievalHint(), node.riskTypes().stream().map(RiskType::valueOf)
                        .collect(Collectors.toUnmodifiableSet()))).toList();
        new OutlineVersion(UUID.randomUUID(), 1, nodes, source.createdBy(), source.createdAt(), "模型候选");
        return json(Map.of("kind", "OUTLINE", "requirementVersionId", source.id(),
                "expectedRevision", expectedRevision, "nodes", nodes));
    }

    private static Map<String, Object> sourceView(RequirementSource source) {
        try {
            Map<String, Object> locator = JSON.readValue(SolutionLocatorJson.write(source.locator()),
                    new TypeReference<>() {});
            return Map.of("documentId", source.documentVersion().documentId(),
                    "versionId", source.documentVersion().versionId(), "locator", locator);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("invalid source locator", failure);
        }
    }

    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("invalid proposal", failure); }
    }

    public record Command(UUID actorId, UUID solutionId, long expectedRevision, String clientKey) {
        public Command {
            Objects.requireNonNull(actorId);
            Objects.requireNonNull(solutionId);
            if (expectedRevision < 0 || clientKey == null || clientKey.isBlank())
                throw new IllegalArgumentException("invalid proposal command");
        }
        @Override public String toString() {
            return "Command[actorId=" + actorId + ", solutionId=" + solutionId + "]";
        }
    }

    private record Context(RequirementVersion version) { }
}
