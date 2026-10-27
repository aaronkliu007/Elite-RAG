package com.yunjihai.rag.qa;

import com.yunjihai.rag.qa.domain.CandidateAnswer;
import com.yunjihai.rag.qa.domain.EvidenceBundle;
import com.yunjihai.rag.qa.domain.EvidenceSupport;
import com.yunjihai.rag.qa.domain.GroundedClaim;
import com.yunjihai.rag.qa.domain.QaEvidence;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExactQuoteCitationSupportVerifierTest {

    @Test
    void acceptsKnownExactQuoteAfterNfkcAndUnicodeWhitespaceNormalization() {
        UUID evidenceId = id(1);
        var bundle = bundle(evidenceId, "制度　自\t今日起生效");
        var answer = answer(evidenceId, "制度 自 今日起生效");

        assertThat(new ExactQuoteCitationSupportVerifier().isSupported(answer, bundle)).isTrue();
    }

    @Test
    void rejectsUnknownEvidenceAndFabricatedQuote() {
        var verifier = new ExactQuoteCitationSupportVerifier();
        var bundle = bundle(id(1), "制度自今日起生效");

        assertThat(verifier.isSupported(answer(id(2), "制度自今日起生效"), bundle)).isFalse();
        assertThat(verifier.isSupported(answer(id(1), "制度从明日起生效"), bundle)).isFalse();
    }

    @Test
    void rejectsClaimThatIsNotAnExactExtractFromItsQuotedEvidence() {
        var verifier = new ExactQuoteCitationSupportVerifier();
        var bundle = bundle(id(1), "制度自今日起生效");
        var answer = new CandidateAnswer(
                CandidateAnswer.Decision.ANSWER,
                "制度自明日起生效",
                List.of(new GroundedClaim(id(10), "制度自明日起生效",
                        List.of(new EvidenceSupport(id(1), "制度自今日起生效")))));

        assertThat(verifier.isSupported(answer, bundle)).isFalse();
    }

    @Test
    void rejectsAnswerTextContainingMaterialOutsideTheVerifiedClaims() {
        var verifier = new ExactQuoteCitationSupportVerifier();
        var bundle = bundle(id(1), "制度自今日起生效");
        var answer = new CandidateAnswer(
                CandidateAnswer.Decision.ANSWER,
                "制度自今日起生效，且适用于所有子公司。",
                List.of(new GroundedClaim(id(10), "制度自今日起生效",
                        List.of(new EvidenceSupport(id(1), "制度自今日起生效")))));

        assertThat(verifier.isSupported(answer, bundle)).isFalse();
    }

    private static CandidateAnswer answer(UUID evidenceId, String quote) {
        return new CandidateAnswer(
                CandidateAnswer.Decision.ANSWER,
                quote,
                List.of(new GroundedClaim(
                        id(10), quote, List.of(new EvidenceSupport(evidenceId, quote)))));
    }

    private static EvidenceBundle bundle(UUID evidenceId, String excerpt) {
        var evidence = new QaEvidence(
                evidenceId, id(2), id(3), id(4), "node-1", "制度", excerpt, 0.9,
                new MarkdownLocator(List.of("第一章"), "node-1"));
        return new EvidenceBundle(List.of(evidence), excerpt.codePointCount(0, excerpt.length()));
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
