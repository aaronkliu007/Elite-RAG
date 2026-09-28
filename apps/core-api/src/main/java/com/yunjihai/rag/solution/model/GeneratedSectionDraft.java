package com.yunjihai.rag.solution.model;

import com.yunjihai.rag.solution.domain.Claim;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record GeneratedSectionDraft(
        String markdown,
        List<Claim> claims,
        Set<UUID> evidenceIds) {

    public GeneratedSectionDraft {
        Objects.requireNonNull(markdown, "markdown");
        markdown = markdown.strip();
        if (markdown.isEmpty()) {
            throw new IllegalArgumentException("section markdown must not be blank");
        }
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
        evidenceIds = Set.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
        var claimIds = new HashSet<UUID>();
        if (claims.stream().anyMatch(claim -> !claimIds.add(claim.claimId()))) {
            throw new IllegalArgumentException("duplicate claim id");
        }
        var cited = new HashSet<UUID>();
        claims.forEach(claim -> cited.addAll(claim.evidenceIds()));
        if (!cited.equals(evidenceIds)) {
            throw new IllegalArgumentException("section evidence does not match claims");
        }
    }

    @Override
    public String toString() {
        return "GeneratedSectionDraft[claimCount=" + claims.size()
                + ", evidenceCount=" + evidenceIds.size() + "]";
    }
}
