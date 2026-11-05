package com.yunjihai.rag.qa;

import com.yunjihai.rag.qa.domain.CandidateAnswer;
import com.yunjihai.rag.qa.domain.EvidenceBundle;
import com.yunjihai.rag.qa.domain.EvidenceSupport;
import com.yunjihai.rag.qa.domain.GroundedClaim;
import com.yunjihai.rag.qa.domain.QaEvidence;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExactQuoteCitationSupportVerifierTest {

    @Test
    void boundsWorkForManyRepeatedQuoteOccurrencesAtTheSupportLimit() {
        var sources = java.util.stream.IntStream.range(1, 16).mapToObj(index -> {
            String text = "甲".repeat(1200);
            return new QaEvidence(id(index), id(200), id(300 + index), id(400 + index), "node-" + index,
                    "标题", text, 0.9, new MarkdownLocator(List.of("章节"), "node-" + index));
        }).toList();
        var supports = sources.stream().map(item -> new EvidenceSupport(item.evidenceId(), "甲")).toList();
        var claims = java.util.stream.IntStream.range(0, 15).mapToObj(index ->
                new GroundedClaim(id(1000 + index), "甲", supports)).toList();
        var answer = new CandidateAnswer(CandidateAnswer.Decision.ANSWER,
                String.join("\n", java.util.Collections.nCopies(15, "甲")), claims);
        var bundle = new EvidenceBundle(sources, 18_000);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () ->
                assertThat(new ExactQuoteCitationSupportVerifier().isSupported(answer, bundle)).isTrue());
    }

    @Test
    void rejectsExcessiveClaimsBeforeInspectingAllQuoteOccurrences() {
        var claims = java.util.stream.IntStream.range(0, 101).mapToObj(index ->
                new GroundedClaim(id(1000 + index), "甲", List.of(new EvidenceSupport(id(1), "甲")))).toList();
        var answer = new CandidateAnswer(CandidateAnswer.Decision.ANSWER,
                String.join("\n", java.util.Collections.nCopies(101, "甲")), claims);
        assertThat(new ExactQuoteCitationSupportVerifier().isSupported(answer, bundle(id(1), "甲"))).isFalse();
    }

    @Test
    void rejectsExcessiveTotalSupports() {
        var sources = java.util.stream.IntStream.range(1, 16).mapToObj(index -> {
            String text = "甲";
            return new QaEvidence(id(index), id(200), id(300 + index), id(400 + index), "node-" + index,
                    "标题", text, 0.9, new MarkdownLocator(List.of("章节"), "node-" + index));
        }).toList();
        var supports = sources.stream().map(item -> new EvidenceSupport(item.evidenceId(), "甲")).toList();
        var claims = java.util.stream.IntStream.range(0, 16).mapToObj(index ->
                new GroundedClaim(id(1000 + index), "甲", supports)).toList();
        var answer = new CandidateAnswer(CandidateAnswer.Decision.ANSWER,
                String.join("\n", java.util.Collections.nCopies(16, "甲")), claims);
        var bundle = new EvidenceBundle(sources, 15);
        assertThat(new ExactQuoteCitationSupportVerifier().isSupported(answer, bundle)).isFalse();
    }

    @Test
    void rejectsClaimThatDropsNegationFromTheFullQuote() {
        var verifier = new ExactQuoteCitationSupportVerifier();
        var bundle = bundle(id(1), "该方案尚未正式批准。");
        var candidate = new CandidateAnswer(CandidateAnswer.Decision.ANSWER, "批准",
                List.of(new GroundedClaim(id(10), "批准",
                        List.of(new EvidenceSupport(id(1), "该方案尚未正式批准")))));
        assertThat(verifier.isSupported(candidate, bundle)).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "该方案未批准。|批准",
            "该方案尚未正式批准。|批准",
            "该方案不予批准。|批准",
            "该方案未经委员会批准。|委员会批准",
            "项目没有及时完成审批。|完成审批",
            "项目预算为100万元。|10",
            "项目预算为100万元。|100",
            "项目收益为-5万元。|5",
            "项目收益为-0.5万元。|.5万元",
            "项目预算为1,000万元。|000万元",
            "项目预算为1e3元。|3",
            "项目预算为1e-3元。|-3元",
            "设备容量为1/2 GB。|2 GB",
            "会议时间为12:30。|30",
            "项目预算为1e+3元。|1",
            "项目预算为二百万元。|百万元",
            "项目预算不低于100万元。|100万元",
            "日志至少保留30天。|保留30天",
            "日志至少保留三十天。|保留三十天",
            "预算不低于人民币100万元。|人民币100万元",
            "项目预算为𐒡𐒠元。|𐒠",
            "该方案未批准，后续已批准。|批准"
    })
    void rejectsQuoteThatDropsNegationOrPartOfANumber(String source, String quote) {
        assertThat(new ExactQuoteCitationSupportVerifier().isSupported(answer(id(1), quote), bundle(id(1), source)))
                .isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "该方案已批准。|批准",
            "项目预算为100万元。|100万元",
            "项目收益为-5万元。|-5万元",
            "项目预算为1,000万元。|1,000万元",
            "项目预算为1e3元。|1e3元",
            "项目预算为二百万元。|二百万元",
            "项目预算不低于100万元。|不低于100万元",
            "该方案未延期，但已批准。|批准",
            "已预约检查。|检查",
            "已预约检查10天。|检查10天",
            "现场预计检查。|检查"
    })
    void preservesClearlyBoundedPositiveAndCompleteNumericQuotes(String source, String quote) {
        assertThat(new ExactQuoteCitationSupportVerifier().isSupported(answer(id(1), quote), bundle(id(1), source)))
                .isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "预算为100万元以上。|100万元",
            "预算为100万元以下。|100万元",
            "项目预算为100万元左右。|100万元",
            "完成期限为30天以内。|30天",
            "预算为100万元至200万元。|100万元",
            "预算为100万元-200万元。|100万元"
    })
    void rejectsAmountsWhoseFollowingQualifierOrRangeIsOmitted(String source,String quote) {
        assertThat(new ExactQuoteCitationSupportVerifier().isSupported(answer(id(1),quote),bundle(id(1),source)))
                .isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "预算为100万元以上。|100万元以上",
            "完成期限为30天以内。|30天以内",
            "预算为100万元至200万元。|100万元至200万元",
            "预算为100万元，已批准。|100万元",
            "项目负责人以上岗位参加评审。|项目负责人"
    })
    void acceptsAmountsWithCompleteFollowingContext(String source,String quote) {
        assertThat(new ExactQuoteCitationSupportVerifier().isSupported(answer(id(1),quote),bundle(id(1),source)))
                .isTrue();
    }
    @Test
    void conservativelyRejectsDoubleNegationThatRequiresSemanticInterpretation() {
        assertThat(new ExactQuoteCitationSupportVerifier().isSupported(
                answer(id(1), "批准"), bundle(id(1), "该方案不得不批准。"))).isFalse();
    }

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
