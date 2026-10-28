package com.yunjihai.rag.infrastructure.model;

import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.solution.verification.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ConservativeCitationVerificationPortTest {
    private static final Instant DEADLINE = Instant.parse("2026-10-28T10:01:00Z");

    @Test void supportsOnlyAnExactEvidenceExcerptAfterWhitespaceNormalization() {
        var input = new ClaimEvidenceInput(id(1), "平台 支持100名用户。", List.of(
                evidence(id(2), "平台　支持100名用户。")));

        assertThat(port().verify(List.of(input), DEADLINE)).containsExactly(
                new ClaimVerification(id(1), CitationVerdict.SUPPORTED, "EXACT_EVIDENCE_TEXT"));
    }

    @Test void paraphraseOrContextualSubstringRequiresHumanConfirmation() {
        var paraphrase = new ClaimEvidenceInput(id(3), "平台可服务100名用户", List.of(
                evidence(id(4), "平台支持100名用户")));
        var missingNegation = new ClaimEvidenceInput(id(5), "支持100名用户", List.of(
                evidence(id(6), "平台不支持100名用户")));

        assertThat(port().verify(List.of(paraphrase, missingNegation), DEADLINE)).containsExactly(
                new ClaimVerification(id(3), CitationVerdict.HUMAN_CONFIRMATION_REQUIRED,
                        "PARAPHRASE_REQUIRES_REVIEW"),
                new ClaimVerification(id(5), CitationVerdict.HUMAN_CONFIRMATION_REQUIRED,
                        "PARAPHRASE_REQUIRES_REVIEW"));
    }

    private static CitationVerificationPort port() {
        return new ConservativeCitationVerificationPort();
    }

    private static Evidence evidence(UUID evidenceId, String excerpt) {
        return new Evidence(id(9), evidenceId, id(10), id(11), "node", "客户需求", excerpt,
                0.9, new MarkdownLocator(List.of("客户需求"), "node"));
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-7000-8000-%012d".formatted(suffix));
    }
}
