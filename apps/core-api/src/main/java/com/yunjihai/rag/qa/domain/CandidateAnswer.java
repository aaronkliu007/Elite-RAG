package com.yunjihai.rag.qa.domain;

import java.util.List;
import java.util.Objects;

public record CandidateAnswer(Decision decision, String answer, List<GroundedClaim> claims) {
    public enum Decision { ANSWER, REFUSE }

    public CandidateAnswer {
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(answer, "answer must not be null");
        claims = List.copyOf(Objects.requireNonNull(claims, "claims must not be null"));
        if (claims.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("claims must not contain null");
        }
        if (decision == Decision.ANSWER
                && (answer.isBlank() || answer.codePointCount(0, answer.length()) > 2_000
                || claims.isEmpty())) {
            throw new IllegalArgumentException("answered candidate payload is invalid");
        }
        if (decision == Decision.REFUSE && (!answer.isBlank() || !claims.isEmpty())) {
            throw new IllegalArgumentException("refused candidate must not contain an answer");
        }
    }

    public CandidateAnswer validateAgainst(EvidenceBundle bundle) {
        Objects.requireNonNull(bundle, "bundle must not be null");
        var known = bundle.evidenceIds();
        boolean unknown = claims.stream()
                .flatMap(claim -> claim.supports().stream())
                .anyMatch(support -> !known.contains(support.evidenceId()));
        if (unknown) throw new IllegalArgumentException("claim references unknown evidence");
        return this;
    }
}
