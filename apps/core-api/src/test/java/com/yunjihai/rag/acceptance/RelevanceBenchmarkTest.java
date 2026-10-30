package com.yunjihai.rag.acceptance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelevanceBenchmarkTest {
    @Test
    void loadsVersionedCasesWithExplicitAndDistinctJudgments() throws Exception {
        var dataset = RelevanceBenchmark.load();
        assertThat(dataset.version()).isEqualTo("synthetic-relevance-v1");
        assertThat(dataset.cases()).hasSize(4).allSatisfy(sample -> assertThat(sample.documents()).hasSize(13));
        assertThat(dataset.cases().stream().flatMap(sample -> sample.conflicts().stream())
                .map(RelevanceBenchmark.Conflict::scope))
                .contains(RelevanceBenchmark.Judgment.RELEVANT, RelevanceBenchmark.Judgment.IRRELEVANT,
                        RelevanceBenchmark.Judgment.UNCERTAIN);
    }

    @Test
    void measuresRelevantRecallAndSeparatesIrrelevantUnknownAndConflictPairs() {
        var sample = new RelevanceBenchmark.Sample("case", "问题", List.of(
                new RelevanceBenchmark.Document("a", "相关甲", RelevanceBenchmark.Judgment.RELEVANT),
                new RelevanceBenchmark.Document("b", "相关乙", RelevanceBenchmark.Judgment.RELEVANT),
                new RelevanceBenchmark.Document("c", "无关甲", RelevanceBenchmark.Judgment.IRRELEVANT),
                new RelevanceBenchmark.Document("d", "无关乙", RelevanceBenchmark.Judgment.IRRELEVANT),
                new RelevanceBenchmark.Document("e", "未知甲", RelevanceBenchmark.Judgment.UNCERTAIN),
                new RelevanceBenchmark.Document("f", "未知乙", RelevanceBenchmark.Judgment.UNCERTAIN)),
                List.of(new RelevanceBenchmark.Conflict("a", "b", RelevanceBenchmark.Judgment.RELEVANT),
                        new RelevanceBenchmark.Conflict("c", "d", RelevanceBenchmark.Judgment.IRRELEVANT),
                        new RelevanceBenchmark.Conflict("e", "f", RelevanceBenchmark.Judgment.UNCERTAIN)));

        var result = RelevanceBenchmark.measure(sample, List.of("a", "c", "d", "e", "unjudged"));
        assertThat(result.relevantTotal()).isEqualTo(2);
        assertThat(result.relevantRetrieved()).isEqualTo(1);
        assertThat(result.irrelevantRetrieved()).isEqualTo(2);
        assertThat(result.uncertainRetrieved()).isEqualTo(1);
        assertThat(result.unjudgedRetrieved()).isEqualTo(1);
        assertThat(result.relevantPairsTotal()).isEqualTo(1);
        assertThat(result.relevantPairsComplete()).isZero();
        assertThat(result.relevantPairsPartial()).isEqualTo(1);
        assertThat(result.irrelevantPairsComplete()).isEqualTo(1);
        assertThat(result.irrelevantPairsPartial()).isZero();
        assertThat(result.uncertainPairsPartial()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateOrUnknownLabelsAndInvalidConflictPairs() {
        var document = new RelevanceBenchmark.Document("a", "正文", RelevanceBenchmark.Judgment.RELEVANT);
        assertThatThrownBy(() -> new RelevanceBenchmark.Sample("case", "问题", List.of(document, document), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RelevanceBenchmark.Sample("case", "问题", List.of(document),
                List.of(new RelevanceBenchmark.Conflict("a", "missing", RelevanceBenchmark.Judgment.RELEVANT))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RelevanceBenchmark.Judgment.valueOf("MAYBE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateRetrievalIdsSoCountsCannotBeInflated() {
        var sample = new RelevanceBenchmark.Sample("case", "问题", List.of(
                new RelevanceBenchmark.Document("a", "正文", RelevanceBenchmark.Judgment.RELEVANT)), List.of());
        assertThatThrownBy(() -> RelevanceBenchmark.measure(sample, List.of("a", "a")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
