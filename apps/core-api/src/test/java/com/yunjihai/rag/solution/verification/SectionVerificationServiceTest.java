package com.yunjihai.rag.solution.verification;

import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.solution.domain.Claim;
import com.yunjihai.rag.solution.domain.RiskType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SectionVerificationServiceTest {

    private static final Instant DEADLINE = Instant.parse("2026-09-28T08:01:00Z");

    @Test
    void rejectsFactualClaimWithoutEvidenceBeforeSemanticVerification() {
        var port = new RecordingPort();
        var service = new SectionVerificationService(port);
        var claim = claim(id(1), "项目周期30天", Set.of(), Set.of(RiskType.DATE_OR_DURATION));

        assertThatThrownBy(() -> service.verify(List.of(claim), List.of(evidence(id(11), "项目周期30天")), DEADLINE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(port.inputs).isEmpty();
    }

    @Test
    void rejectsEvidenceOutsideTheCurrentWhitelistBeforeSemanticVerification() {
        var port = new RecordingPort();
        var service = new SectionVerificationService(port);
        var claim = claim(id(1), "项目周期30天", Set.of(id(99)), Set.of(RiskType.DATE_OR_DURATION));

        assertThatThrownBy(() -> service.verify(List.of(claim), List.of(evidence(id(11), "项目周期30天")), DEADLINE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(port.inputs).isEmpty();
    }

    @Test
    void rejectsDuplicateClaimIdsBeforeSemanticVerification() {
        var port = new RecordingPort();
        var service = new SectionVerificationService(port);
        var first = claim(id(1), "项目周期30天", Set.of(id(11)), Set.of(RiskType.DATE_OR_DURATION));
        var duplicate = claim(id(1), "报价100万元", Set.of(id(12)), Set.of(RiskType.AMOUNT_OR_PRICE));

        assertThatThrownBy(() -> service.verify(
                List.of(first, duplicate),
                List.of(evidence(id(11), "项目周期30天"), evidence(id(12), "报价100万元")),
                DEADLINE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(port.inputs).isEmpty();
    }

    @Test
    void rejectsContradictingDurationAndAmountWithoutCallingSemanticPort() {
        var port = new RecordingPort();
        var service = new SectionVerificationService(port);
        var duration = claim(id(1), "项目周期30天", Set.of(id(11)), Set.of(RiskType.DATE_OR_DURATION));
        var amount = claim(id(2), "报价100万元", Set.of(id(12)), Set.of(RiskType.AMOUNT_OR_PRICE));

        var report = service.verify(
                List.of(duration, amount),
                List.of(evidence(id(11), "项目周期60天"), evidence(id(12), "报价10万元")),
                DEADLINE);

        assertThat(report.verifications())
                .extracting(ClaimVerification::verdict)
                .containsExactly(CitationVerdict.UNSUPPORTED, CitationVerdict.UNSUPPORTED);
        assertThat(port.inputs).isEmpty();
    }

    @Test
    void matchingNumberAndUnitContinueToSemanticVerificationWithDeadline() {
        var port = new RecordingPort();
        var service = new SectionVerificationService(port);
        var claim = claim(id(1), "系统支持100名用户", Set.of(id(11)), Set.of());

        var report = service.verify(
                List.of(claim), List.of(evidence(id(11), "系统支持100名用户")), DEADLINE);

        assertThat(report.verifications()).singleElement().satisfies(result -> {
            assertThat(result.claimId()).isEqualTo(id(1));
            assertThat(result.verdict()).isEqualTo(CitationVerdict.SUPPORTED);
        });
        assertThat(port.deadline).isEqualTo(DEADLINE);
        assertThat(port.inputs).singleElement().satisfies(input -> {
            assertThat(input.claimId()).isEqualTo(id(1));
            assertThat(input.evidence()).extracting(Evidence::evidenceId).containsExactly(id(11));
        });
    }

    @Test
    void semanticUnknownDuplicateMissingNullAndFailureFallBackToHumanReview() {
        var claim = claim(id(1), "系统支持100名用户", Set.of(id(11)), Set.of());
        var evidence = List.of(evidence(id(11), "系统支持100名用户"));
        List<CitationVerificationPort> unavailablePorts = List.of(
                (inputs, deadline) -> List.of(new ClaimVerification(
                        id(999), CitationVerdict.SUPPORTED, "UNKNOWN_CLAIM")),
                (inputs, deadline) -> List.of(
                        new ClaimVerification(id(1), CitationVerdict.SUPPORTED, "FIRST_RESULT"),
                        new ClaimVerification(id(1), CitationVerdict.SUPPORTED, "DUPLICATE_RESULT")),
                (inputs, deadline) -> List.of(),
                (inputs, deadline) -> null,
                (inputs, deadline) -> {
                    throw new IllegalStateException("供应商正文不得外泄");
                });

        for (var port : unavailablePorts) {
            var report = new SectionVerificationService(port)
                    .verify(List.of(claim), evidence, DEADLINE);
            assertThat(report.verifications()).singleElement().satisfies(result -> {
                assertThat(result.claimId()).isEqualTo(id(1));
                assertThat(result.verdict()).isEqualTo(CitationVerdict.HUMAN_CONFIRMATION_REQUIRED);
                assertThat(result.reasonCode()).isEqualTo("SEMANTIC_UNAVAILABLE");
            });
        }
    }

    @Test
    void supportedHighRiskClaimStillRequiresHumanConfirmation() {
        var claim = claim(id(1), "项目周期30天", Set.of(id(11)), Set.of(RiskType.DATE_OR_DURATION));
        CitationVerificationPort port = (inputs, deadline) -> List.of(
                new ClaimVerification(id(1), CitationVerdict.SUPPORTED, "SEMANTIC_SUPPORTED"));

        var report = new SectionVerificationService(port)
                .verify(List.of(claim), List.of(evidence(id(11), "项目周期30天")), DEADLINE);

        assertThat(report.verifications()).singleElement().satisfies(result -> {
            assertThat(result.verdict()).isEqualTo(CitationVerdict.HUMAN_CONFIRMATION_REQUIRED);
            assertThat(result.reasonCode()).isEqualTo("HIGH_RISK_CONFIRMATION_REQUIRED");
        });
    }

    @Test
    void partialAndUnsupportedSemanticResultsRemainBlocking() {
        var first = claim(id(1), "交付范围包含培训", Set.of(id(11)), Set.of());
        var second = claim(id(2), "系统提供永久维保", Set.of(id(12)), Set.of());
        CitationVerificationPort port = (inputs, deadline) -> List.of(
                new ClaimVerification(id(1), CitationVerdict.PARTIAL, "PARTIALLY_SUPPORTED"),
                new ClaimVerification(id(2), CitationVerdict.UNSUPPORTED, "NOT_SUPPORTED"));

        var report = new SectionVerificationService(port).verify(
                List.of(first, second),
                List.of(evidence(id(11), "交付范围包含基础培训"), evidence(id(12), "提供一年维保")),
                DEADLINE);

        assertThat(report.verifications())
                .extracting(ClaimVerification::verdict)
                .containsExactly(CitationVerdict.PARTIAL, CitationVerdict.UNSUPPORTED);
        assertThat(report.hasBlockingVerdict()).isTrue();
    }

    private static Claim claim(
            UUID claimId,
            String text,
            Set<UUID> evidenceIds,
            Set<RiskType> riskTypes) {
        return new Claim(claimId, text, true, evidenceIds, riskTypes);
    }

    private static Evidence evidence(UUID evidenceId, String excerpt) {
        return new Evidence(
                evidenceId,
                id(200),
                id(201),
                "node-1",
                "客户需求",
                excerpt,
                0.9,
                new MarkdownLocator(List.of("客户需求"), "node-1"));
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
    }

    private static final class RecordingPort implements CitationVerificationPort {
        private List<ClaimEvidenceInput> inputs = new ArrayList<>();
        private Instant deadline;

        @Override
        public List<ClaimVerification> verify(List<ClaimEvidenceInput> inputs, Instant deadline) {
            this.inputs = List.copyOf(inputs);
            this.deadline = deadline;
            return inputs.stream()
                    .map(input -> new ClaimVerification(
                            input.claimId(), CitationVerdict.SUPPORTED, "SEMANTIC_SUPPORTED"))
                    .toList();
        }
    }
}
