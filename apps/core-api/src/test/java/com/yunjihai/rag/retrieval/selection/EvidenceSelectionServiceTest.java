package com.yunjihai.rag.retrieval.selection;

import com.yunjihai.rag.qa.conflict.DeterministicEvidenceConflictDetector;
import com.yunjihai.rag.retrieval.domain.Candidate;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.domain.RecallSource;
import com.yunjihai.rag.retrieval.domain.RetrievalPolicy;
import com.yunjihai.rag.retrieval.domain.ScoredCandidate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceSelectionServiceTest {

    @Test
    void removesChineseNearDuplicatesAfterUnicodeNormalization() {
        var first = scored(1, 101, "云基华海， AI  知识库方案！");
        var punctuationWhitespaceAndCaseVariant = scored(2, 102, "云基华海 ai 知识库方案");
        var distinct = scored(3, 103, "完全不同的项目交付验收规范");
        var shortText = scored(4, 104, "好！");
        var shortDuplicate = scored(5, 105, "好");
        var service = new EvidenceSelectionService(RetrievalPolicy.defaults());

        var selected = service.select(List.of(
                first,
                punctuationWhitespaceAndCaseVariant,
                distinct,
                shortText,
                shortDuplicate), 8);

        assertThat(selected).containsExactly(first, distinct, shortText);
        assertThatThrownBy(selected::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void keepsSemanticNumericPunctuationWhileRemovingLayoutPunctuation() {
        var decimalAmount = scored(6, 106, "报价1.0万元");
        var layoutVariant = scored(7, 107, "报价：1.0万元！");
        var tenfoldAmount = scored(8, 108, "报价10万元");

        var selected = new EvidenceSelectionService(RetrievalPolicy.defaults()).select(
                List.of(decimalAmount, layoutVariant, tenfoldAmount), 8);

        assertThat(selected).containsExactly(decimalAmount, tenfoldAmount);
    }

    @Test
    void preservesNearIdenticalEvidenceWhenNegationOrAmountChanges() {
        String context = "经项目评审委员会审议，团队完成需求分析与安全审查，供应商提供交付材料，相关人员应按项目计划记录进度并执行验收流程。";
        var approved = scored(21, 121, context + "该方案已经批准。");
        var rejected = scored(22, 122, context + "该方案未批准。");
        var budget = scored(23, 123, context + "实施预算为100万元。");
        var changedBudget = scored(24, 124, context + "实施预算为101万元。");

        var selected = new EvidenceSelectionService(RetrievalPolicy.defaults()).select(
                List.of(approved, rejected, budget, changedBudget), 8);

        assertThat(selected).containsExactly(approved, rejected, budget, changedBudget);
        var approvalEvidence = selected.subList(0, 2).stream().map(item -> {
            var candidate = item.candidate();
            return new Evidence(candidate.spaceId(), candidate.candidateId(), candidate.documentId(),
                    candidate.versionId(), candidate.nodeId(), candidate.title(), candidate.text(),
                    item.score(), candidate.locator());
        }).toList();
        assertThat(new DeterministicEvidenceConflictDetector().hasConflict(approvalEvidence)).isTrue();
    }

    @Test
    void enforcesPerDocumentCapBeforeMovingToTheNextCandidate() {
        var sameDocument = uuid(500);
        var first = scored(10, sameDocument, "甲甲甲甲");
        var second = scored(11, sameDocument, "乙乙乙乙");
        var third = scored(12, sameDocument, "丙丙丙丙");
        var fourth = scored(13, sameDocument, "丁丁丁丁");
        var anotherDocument = scored(14, uuid(501), "戊戊戊戊");

        var selected = new EvidenceSelectionService(RetrievalPolicy.defaults()).select(
                List.of(first, second, third, fourth, anotherDocument), 8);

        assertThat(selected).containsExactly(first, second, third, anotherDocument);
    }

    @Test
    void strictlyHonorsEightAndFifteenWithoutPadding() {
        var input = new ArrayList<ScoredCandidate>();
        for (int index = 0; index < 20; index++) {
            input.add(scored(
                    100 + index,
                    600 + index,
                    Character.toString(0x4E00 + index).repeat(4)));
        }
        var service = new EvidenceSelectionService(RetrievalPolicy.defaults());

        assertThat(service.select(input, 8)).containsExactlyElementsOf(input.subList(0, 8));
        assertThat(service.select(input, 15)).containsExactlyElementsOf(input.subList(0, 15));
        assertThat(service.select(input.subList(0, 2), 8)).hasSize(2);
        assertThatThrownBy(() -> service.select(input, 7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.select(input, 16))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ScoredCandidate scored(int candidateSeed, int documentSeed, String text) {
        return scored(candidateSeed, uuid(documentSeed), text);
    }

    private static ScoredCandidate scored(int candidateSeed, UUID documentId, String text) {
        var candidate = new Candidate(
                uuid(9000),
                uuid(candidateSeed),
                documentId,
                uuid(1000 + candidateSeed),
                "node-" + candidateSeed,
                "标题-" + candidateSeed,
                text,
                new MarkdownLocator(List.of("章节"), "node-" + candidateSeed),
                RecallSource.LEXICAL,
                1,
                1.0);
        return new ScoredCandidate(
                candidate,
                1000 - candidateSeed,
                1,
                Set.of(RecallSource.LEXICAL));
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
