package com.yunjihai.rag.qa.domain;

import com.yunjihai.rag.qa.QaPolicy;
import com.yunjihai.rag.retrieval.domain.Evidence;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record EvidenceBundle(List<QaEvidence> evidence, int totalCodePoints) {

    public EvidenceBundle {
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
        if (evidence.isEmpty() || evidence.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("evidence bundle must not be empty");
        }
        var ids = new HashSet<UUID>();
        if (evidence.stream().anyMatch(item -> !ids.add(item.evidenceId()))) {
            throw new IllegalArgumentException("evidence ids must be unique");
        }
        int measured = evidence.stream()
                .mapToInt(item -> item.excerpt().codePointCount(0, item.excerpt().length()))
                .sum();
        if (totalCodePoints != measured) {
            throw new IllegalArgumentException("evidence bundle size is inconsistent");
        }
    }

    public static EvidenceBundle from(List<Evidence> source, QaPolicy policy) {
        Objects.requireNonNull(source, "source evidence must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        if (source.size() < policy.minEvidenceCount() || source.size() > policy.maxEvidenceCount()) {
            throw new IllegalArgumentException("evidence count is outside policy");
        }
        var mapped = source.stream().map(QaEvidence::from).toList();
        if (mapped.stream().anyMatch(item -> item.excerpt().codePointCount(
                0, item.excerpt().length()) > policy.maxEvidenceCodePoints())) {
            throw new IllegalArgumentException("evidence length is outside policy");
        }
        int total = mapped.stream()
                .mapToInt(item -> item.excerpt().codePointCount(0, item.excerpt().length()))
                .sum();
        if (total > policy.maxModelContextCodePoints()) {
            throw new IllegalArgumentException("evidence context is outside policy");
        }
        return new EvidenceBundle(mapped, total);
    }

    public Set<UUID> evidenceIds() {
        return evidence.stream().map(QaEvidence::evidenceId).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
