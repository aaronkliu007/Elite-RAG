package com.yunjihai.rag.qa.domain;

import com.yunjihai.rag.qa.QaPolicy;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QaDomainContractTest {

    @Test
    void normalizes_nfkc_and_collapses_unicode_whitespace() {
        var question = QaQuestion.normalize("  ＡＩ\t知识\u3000检索\n方案  ");

        assertThat(question.value()).isEqualTo("AI 知识 检索 方案");
        assertThat(question.codePointLength()).isEqualTo(11);
    }

    @Test
    void rejects_blank_or_more_than_2000_code_points() {
        assertThatThrownBy(() -> QaQuestion.normalize(" \t\u3000\n "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QaQuestion.normalize("知".repeat(2001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(QaQuestion.normalize("😀".repeat(2000)).codePointLength()).isEqualTo(2000);
    }

    @Test
    void copies_all_collections() {
        var raw = new ArrayList<Evidence>();
        for (int index = 0; index < 8; index++) {
            raw.add(evidence(index));
        }
        var bundle = EvidenceBundle.from(raw, QaPolicy.defaults());
        var supports = new ArrayList<>(List.of(
                new EvidenceSupport(bundle.evidence().getFirst().evidenceId(), "原文引句")));
        var claims = new ArrayList<>(List.of(
                new GroundedClaim(id(900), "有证据的声明", supports)));
        var answer = new CandidateAnswer(
                CandidateAnswer.Decision.ANSWER, "回答", claims);

        raw.clear();
        supports.clear();
        claims.clear();

        assertThat(bundle.evidence()).hasSize(8);
        assertThat(answer.claims()).hasSize(1);
        assertThat(answer.claims().getFirst().supports()).hasSize(1);
        assertThatThrownBy(() -> bundle.evidence().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> answer.claims().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejects_unknown_or_duplicate_evidence_support() {
        var bundle = EvidenceBundle.from(
                java.util.stream.IntStream.range(0, 8).mapToObj(QaDomainContractTest::evidence).toList(),
                QaPolicy.defaults());
        UUID known = bundle.evidence().getFirst().evidenceId();

        assertThatThrownBy(() -> new GroundedClaim(
                id(901), "重复支持", List.of(
                        new EvidenceSupport(known, "引句一"),
                        new EvidenceSupport(known, "引句二"))))
                .isInstanceOf(IllegalArgumentException.class);

        var unknown = new CandidateAnswer(
                CandidateAnswer.Decision.ANSWER,
                "回答",
                List.of(new GroundedClaim(
                        id(902), "未知支持", List.of(new EvidenceSupport(id(999), "伪造引句")))));
        assertThatThrownBy(() -> unknown.validateAgainst(bundle))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceSupport(known, " \t "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforces_evidence_bundle_range_and_unique_ids() {
        assertThatThrownBy(() -> EvidenceBundle.from(
                java.util.stream.IntStream.range(0, 7).mapToObj(QaDomainContractTest::evidence).toList(),
                QaPolicy.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvidenceBundle.from(
                java.util.stream.IntStream.range(0, 16).mapToObj(QaDomainContractTest::evidence).toList(),
                QaPolicy.defaults()))
                .isInstanceOf(IllegalArgumentException.class);

        var duplicate = new ArrayList<Evidence>();
        for (int index = 0; index < 8; index++) duplicate.add(evidence(index));
        duplicate.set(7, evidence(0));
        assertThatThrownBy(() -> EvidenceBundle.from(duplicate, QaPolicy.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void terminal_state_cannot_advance() {
        assertThat(QaStatus.RECEIVED.advanceTo(QaStatus.RETRIEVING))
                .isEqualTo(QaStatus.RETRIEVING);
        assertThat(QaStatus.VERIFYING.advanceTo(QaStatus.ANSWERED))
                .isEqualTo(QaStatus.ANSWERED);
        for (var terminal : Set.of(QaStatus.ANSWERED, QaStatus.REFUSED, QaStatus.FAILED)) {
            assertThat(terminal.isTerminal()).isTrue();
            assertThatThrownBy(() -> terminal.advanceTo(QaStatus.RETRIEVING))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void outcomes_have_fixed_terminal_status_and_safe_payloads() {
        var claim = new CitationClaim(id(903), "声明", List.of(id(100)));
        QaOutcome answered = new QaOutcome.Answered(id(1), "回答", List.of(claim), false);
        QaOutcome refused = new QaOutcome.Refused(
                id(2), RefusalReason.INSUFFICIENT_EVIDENCE, true);
        QaOutcome failed = new QaOutcome.Failed(id(3), "MODEL_UNAVAILABLE");

        assertThat(answered.status()).isEqualTo(QaStatus.ANSWERED);
        assertThat(refused.status()).isEqualTo(QaStatus.REFUSED);
        assertThat(failed.status()).isEqualTo(QaStatus.FAILED);
        assertThatThrownBy(() -> new QaOutcome.Failed(id(4), "包含空格的秘密错误"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Evidence evidence(int index) {
        return new Evidence(
                id(10), id(100 + index), id(200 + index), id(300 + index),
                "node-" + index, "标题-" + index, "证据正文-" + index, 1.0 - index * 0.01,
                new MarkdownLocator(List.of("章节"), "node-" + index));
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
