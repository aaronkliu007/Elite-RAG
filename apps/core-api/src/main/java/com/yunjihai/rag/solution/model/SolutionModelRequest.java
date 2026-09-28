package com.yunjihai.rag.solution.model;

import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.solution.domain.OutlineNode;
import com.yunjihai.rag.solution.domain.RequirementItem;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record SolutionModelRequest(
        UUID requestId,
        String idempotencyKey,
        ModelScenario scenario,
        PromptRef prompt,
        List<RequirementItem> confirmedRequirements,
        Optional<OutlineNode> section,
        List<String> templateRules,
        List<Evidence> evidence,
        Instant deadline) {

    public SolutionModelRequest {
        Objects.requireNonNull(requestId, "requestId");
        idempotencyKey = requiredText(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(prompt, "prompt");
        confirmedRequirements = List.copyOf(Objects.requireNonNull(
                confirmedRequirements, "confirmedRequirements"));
        section = Objects.requireNonNull(section, "section");
        templateRules = List.copyOf(Objects.requireNonNull(templateRules, "templateRules"));
        if (templateRules.stream().anyMatch(rule -> rule == null || rule.isBlank())) {
            throw new IllegalArgumentException("template rules contain blank values");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        Objects.requireNonNull(deadline, "deadline");
    }

    private static String requiredText(String value, String field) {
        Objects.requireNonNull(value, field);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    @Override
    public String toString() {
        return "SolutionModelRequest[requestId=" + requestId
                + ", scenario=" + scenario
                + ", prompt=" + prompt
                + ", requirementCount=" + confirmedRequirements.size()
                + ", sectionPresent=" + section.isPresent()
                + ", templateRuleCount=" + templateRules.size()
                + ", evidenceCount=" + evidence.size()
                + ", deadline=" + deadline + "]";
    }
}
