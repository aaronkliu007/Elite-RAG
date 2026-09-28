package com.yunjihai.rag.solution.verification;

import java.time.Instant;
import java.util.List;

@FunctionalInterface
public interface CitationVerificationPort {

    List<ClaimVerification> verify(List<ClaimEvidenceInput> inputs, Instant deadline);
}
