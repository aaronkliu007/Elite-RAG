package com.yunjihai.rag.solution.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.infrastructure.postgres.SolutionLocatorJson;
import com.yunjihai.rag.solution.application.SolutionCommandResult;
import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.retrieval.domain.*;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import java.time.Instant;
import java.util.*;

public final class SolutionDtos {
    private static final ObjectMapper JSON = new ObjectMapper();
    private SolutionDtos() { }

    public record CreateRequest(String name, List<RequirementItemRequest> items, Long expectedRevision) { }
    public record ReviseRequirementsRequest(List<RequirementItemRequest> items,
            String changeReason, Long expectedRevision) { }
    public record ConfirmRequest(Long expectedRevision) { }
    public record ReviseOutlineRequest(List<OutlineNodeRequest> nodes, String changeReason, Long expectedRevision) { }
    public record RequirementItemRequest(String category, String text, List<SourceRequest> sources) { }
    public record SourceRequest(UUID documentId, UUID versionId, Map<String, Object> locator) { }
    public record OutlineNodeRequest(String title, int level, int order, boolean required,
            String objective, String retrievalHint, Set<String> riskTypes) { }

    public record CommandResponse(UUID solutionId, long revision, UUID requirementVersionId,
            Optional<UUID> outlineVersionId, SolutionPhase phase) {
        static CommandResponse from(SolutionCommandResult result) {
            return new CommandResponse(result.solutionId(), result.revision(), result.requirementVersionId(),
                    result.outlineVersionId(), result.phase());
        }
    }

    public record Summary(UUID id, UUID spaceId, String name, SolutionPhase phase,
            long revision, Instant createdAt, Instant updatedAt) { }

    public record Detail(UUID id, UUID spaceId, String name, SolutionPhase phase, long revision,
            long solutionRevision, Instant createdAt, RequirementVersionView requirementVersion,
            OutlineVersionView outlineVersion, ConfirmationView requirementConfirmation,
            ConfirmationView outlineConfirmation, boolean sourceAccessValid,
            Map<UUID, UUID> adoptedSectionVersionIds, UUID finalizedVersionId,
            Set<String> allowedActions) { }

    public record RequirementVersionView(UUID id, long number, List<RequirementItemView> items,
            UUID createdBy, Instant createdAt, String changeReason) {
        static RequirementVersionView from(RequirementVersion version) {
            return new RequirementVersionView(version.id(), version.number(),
                    version.items().stream().map(RequirementItemView::from).toList(),
                    version.createdBy(), version.createdAt(), version.changeReason());
        }
    }

    public record RequirementItemView(UUID itemId, RequirementCategory category, String text,
            List<SourceView> sources) {
        static RequirementItemView from(RequirementItem item) {
            return new RequirementItemView(item.itemId(), item.category(), item.text(),
                    item.sources().stream().map(SourceView::from).toList());
        }
    }

    public record SourceView(UUID documentId, UUID versionId, Map<String, Object> locator) {
        static SourceView from(RequirementSource source) {
            return new SourceView(source.documentVersion().documentId(), source.documentVersion().versionId(),
                    locatorView(source.locator()));
        }
    }

    public record OutlineVersionView(UUID id, long number, List<OutlineNodeView> nodes,
            UUID createdBy, Instant createdAt, String changeReason) {
        static OutlineVersionView from(OutlineVersion version) {
            return new OutlineVersionView(version.id(), version.number(),
                    version.nodes().stream().map(OutlineNodeView::from).toList(),
                    version.createdBy(), version.createdAt(), version.changeReason());
        }
    }

    public record OutlineNodeView(UUID sectionId, String title, int level, int order, boolean required,
            String objective, String retrievalHint, Set<RiskType> riskTypes) {
        static OutlineNodeView from(OutlineNode node) {
            return new OutlineNodeView(node.sectionId(), node.title(), node.level(), node.order(),
                    node.required(), node.objective(), node.retrievalHint(), node.riskTypes());
        }
    }

    public record ConfirmationView(UUID versionId, UUID confirmedBy, Instant confirmedAt, boolean valid) {
        static ConfirmationView from(VersionConfirmation confirmation, boolean valid) {
            return new ConfirmationView(confirmation.versionId(), confirmation.confirmedBy(), confirmation.confirmedAt(), valid);
        }
    }

    static List<RequirementItem> items(List<RequirementItemRequest> request,
            UUID actor, UUID resource, String clientKey) {
        if (request == null || request.isEmpty()) throw new IllegalArgumentException("items are required");
        var result = new ArrayList<RequirementItem>();
        for (int i = 0; i < request.size(); i++) {
            var item = Objects.requireNonNull(request.get(i));
            if (item.sources() == null) throw new IllegalArgumentException("sources are required");
            var sources = item.sources().stream().map(source -> {
                Objects.requireNonNull(source);
                if (source.documentId() == null || source.versionId() == null || source.locator() == null)
                    throw new IllegalArgumentException("source is incomplete");
                try {
                    return new RequirementSource(new DocumentVersionRef(source.documentId(), source.versionId()),
                            SolutionLocatorJson.read(JSON.writeValueAsString(source.locator())));
                } catch (JsonProcessingException failure) {
                    throw new IllegalArgumentException("invalid source locator", failure);
                }
            }).toList();
            result.add(new RequirementItem(stableId(actor, resource, clientKey, "item", i),
                    RequirementCategory.valueOf(item.category()), item.text(), sources));
        }
        return List.copyOf(result);
    }

    static List<OutlineNode> nodes(List<OutlineNodeRequest> request,
            UUID actor, UUID resource, String clientKey) {
        if (request == null || request.isEmpty()) throw new IllegalArgumentException("nodes are required");
        var result = new ArrayList<OutlineNode>();
        for (int i = 0; i < request.size(); i++) {
            var node = Objects.requireNonNull(request.get(i));
            if (node.riskTypes() == null) throw new IllegalArgumentException("risk types are required");
            var risks = new HashSet<RiskType>();
            node.riskTypes().forEach(value -> risks.add(RiskType.valueOf(value)));
            result.add(new OutlineNode(stableId(actor, resource, clientKey, "section", i),
                    node.title(), node.level(), node.order(), node.required(), node.objective(),
                    node.retrievalHint(), risks));
        }
        return List.copyOf(result);
    }

    private static UUID stableId(UUID actor, UUID resource, String key, String type, int index) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("idempotency key is required");
        String seed = actor + ":" + resource + ":" + key + ":" + type + ":" + index;
        return UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Map<String, Object> locatorView(SourceLocator locator) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> values = JSON.readValue(SolutionLocatorJson.write(locator), Map.class);
            return Collections.unmodifiableMap(new LinkedHashMap<>(values));
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("invalid locator", failure);
        }
    }
}
