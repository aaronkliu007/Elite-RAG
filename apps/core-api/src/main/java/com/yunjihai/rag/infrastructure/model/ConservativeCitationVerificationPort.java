package com.yunjihai.rag.infrastructure.model;

import com.yunjihai.rag.solution.verification.CitationVerdict;
import com.yunjihai.rag.solution.verification.CitationVerificationPort;
import com.yunjihai.rag.solution.verification.ClaimEvidenceInput;
import com.yunjihai.rag.solution.verification.ClaimVerification;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ConservativeCitationVerificationPort implements CitationVerificationPort {
    @Override
    public List<ClaimVerification> verify(List<ClaimEvidenceInput> inputs, Instant deadline) {
        Objects.requireNonNull(inputs);
        Objects.requireNonNull(deadline);
        var results = new ArrayList<ClaimVerification>(inputs.size());
        for (var input : inputs) {
            var statement = normalize(input.statement());
            boolean exact = input.evidence().stream()
                    .anyMatch(evidence -> normalize(evidence.excerpt()).equals(statement));
            results.add(new ClaimVerification(input.claimId(),
                    exact ? CitationVerdict.SUPPORTED : CitationVerdict.HUMAN_CONFIRMATION_REQUIRED,
                    exact ? "EXACT_EVIDENCE_TEXT" : "PARAPHRASE_REQUIRES_REVIEW"));
        }
        return List.copyOf(results);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ").strip();
    }
}
