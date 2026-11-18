package com.yunjihai.rag.acceptance;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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
    void loadsExpandedConflictAndAmbiguityCasesWithoutChangingTheOriginalBenchmark() throws Exception {
        try (var input = RelevanceBenchmarkTest.class.getResourceAsStream("/relevance-benchmark-v2.json")) {
            assertThat(input).as("expanded benchmark resource").isNotNull();
            var dataset = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    input, RelevanceBenchmark.Dataset.class);
            assertThat(dataset.version()).isEqualTo("synthetic-relevance-v2");
            assertThat(dataset.cases()).hasSize(8);
            assertThat(dataset.cases()).allSatisfy(sample -> {
                assertThat(sample.documents()).hasSize(10);
                assertThat(sample.documents().stream().map(RelevanceBenchmark.Document::judgment))
                        .contains(RelevanceBenchmark.Judgment.RELEVANT, RelevanceBenchmark.Judgment.IRRELEVANT,
                                RelevanceBenchmark.Judgment.UNCERTAIN);
            });
            assertThat(dataset.cases().stream().flatMap(sample -> sample.conflicts().stream())
                    .map(RelevanceBenchmark.Conflict::scope)).contains(
                            RelevanceBenchmark.Judgment.RELEVANT, RelevanceBenchmark.Judgment.IRRELEVANT,
                            RelevanceBenchmark.Judgment.UNCERTAIN);
        }
    }

    @Test
    void ambiguousConflictPairsRemainProtectedEvenWhenOneDocumentIsLabeledIrrelevant() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        try (var source=RelevanceBenchmarkTest.class.getResourceAsStream("/relevance-benchmark-v3.json")) {
            var dataset=json.readValue(java.util.Objects.requireNonNull(source),RelevanceBenchmark.Dataset.class);
            assertThat(dataset.version()).isEqualTo("synthetic-relevance-v3");
            var budget=dataset.cases().stream().filter(item->item.id().equals("budget-conflict")).findFirst().orElseThrow();
            var all=budget.documents().stream().map(RelevanceBenchmark.Document::id).toList();
            var withoutOtherProject=all.stream().filter(id->!id.equals("d3")).toList();
            var result=RelevanceBenchmark.assessScreening(budget,all,withoutOtherProject);
            assertThat(result.removedIrrelevant()).isEqualTo(1);
            assertThat(result.harmedUncertainPairs()).isEqualTo(1);
            assertThat(result.preservesAvailableSafetyEvidence()).isFalse();
            var names=dataset.cases().stream().filter(item->item.id().equals("name-substring")).findFirst().orElseThrow();
            assertThat(names.conflicts()).anySatisfy(pair->{
                assertThat(Set.of(pair.first(),pair.second())).containsExactlyInAnyOrder("d1","d5");
                assertThat(pair.scope()).isEqualTo(RelevanceBenchmark.Judgment.UNCERTAIN);
            });
        }
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
    void screeningAssessmentSeparatesUnsafeRemovalsAndPreexistingMissingConflicts() {
        var sample = new RelevanceBenchmark.Sample("case", "问题", List.of(
                new RelevanceBenchmark.Document("a", "相关甲", RelevanceBenchmark.Judgment.RELEVANT),
                new RelevanceBenchmark.Document("b", "相关乙", RelevanceBenchmark.Judgment.RELEVANT),
                new RelevanceBenchmark.Document("c", "无关", RelevanceBenchmark.Judgment.IRRELEVANT),
                new RelevanceBenchmark.Document("d", "不确定甲", RelevanceBenchmark.Judgment.UNCERTAIN),
                new RelevanceBenchmark.Document("e", "不确定乙", RelevanceBenchmark.Judgment.UNCERTAIN)),
                List.of(new RelevanceBenchmark.Conflict("a", "b", RelevanceBenchmark.Judgment.RELEVANT),
                        new RelevanceBenchmark.Conflict("d", "e", RelevanceBenchmark.Judgment.UNCERTAIN)));
        var result = RelevanceBenchmark.assessScreening(sample,
                List.of("a", "b", "c", "d", "unjudged"), List.of("a"));
        assertThat(result.removedRelevant()).isEqualTo(1);
        assertThat(result.removedIrrelevant()).isEqualTo(1);
        assertThat(result.removedUncertain()).isEqualTo(1);
        assertThat(result.removedUnjudged()).isEqualTo(1);
        assertThat(result.harmedRelevantPairs()).isEqualTo(1);
        assertThat(result.preexistingIncompletePairs()).isEqualTo(1);
        assertThat(result.preservesAvailableSafetyEvidence()).isFalse();
        var safe = RelevanceBenchmark.assessScreening(sample,
                List.of("a", "b", "c", "d", "unjudged"), List.of("a", "b", "d", "unjudged"));
        assertThat(safe.preservesAvailableSafetyEvidence()).isTrue();
        assertThat(safe.preexistingIncompletePairs()).isEqualTo(1);
    }

    @Test
    void mixedJudgmentConflictRequiresBothAvailableSidesToBePreserved() {
        var sample = new RelevanceBenchmark.Sample("mixed", "项目预算是多少？", List.of(
                new RelevanceBenchmark.Document("a", "预算100万元", RelevanceBenchmark.Judgment.RELEVANT),
                new RelevanceBenchmark.Document("b", "混合资料含预算120万元", RelevanceBenchmark.Judgment.IRRELEVANT),
                new RelevanceBenchmark.Document("c", "旧版预算记录", RelevanceBenchmark.Judgment.UNCERTAIN)),
                List.of(new RelevanceBenchmark.Conflict("a", "b", RelevanceBenchmark.Judgment.RELEVANT),
                        new RelevanceBenchmark.Conflict("a", "c", RelevanceBenchmark.Judgment.UNCERTAIN)));
        var discardedSide = RelevanceBenchmark.assessScreening(sample,
                List.of("a","b","c"), List.of("a","c"));
        assertThat(discardedSide.removedRelevant()).isZero();
        assertThat(discardedSide.removedUncertain()).isZero();
        assertThat(discardedSide.harmedRelevantPairs()).isEqualTo(1);
        assertThat(discardedSide.preservesAvailableSafetyEvidence()).isFalse();
        var complete = RelevanceBenchmark.assessScreening(sample,
                List.of("a","b","c"), List.of("a","b","c"));
        assertThat(complete.preservesAvailableSafetyEvidence()).isTrue();
        var lostUncertain = RelevanceBenchmark.assessScreening(sample,
                List.of("a","b","c"), List.of("a","b"));
        assertThat(lostUncertain.harmedUncertainPairs()).isEqualTo(1);
        assertThat(lostUncertain.preservesAvailableSafetyEvidence()).isFalse();
    }
    @Test
    void screeningAssessmentRejectsDuplicatesNewCandidatesAndEmptyInputs() {
        var sample = new RelevanceBenchmark.Sample("case", "问题", List.of(
                new RelevanceBenchmark.Document("a", "相关", RelevanceBenchmark.Judgment.RELEVANT)), List.of());
        assertThatThrownBy(() -> RelevanceBenchmark.assessScreening(sample, List.of("a", "a"), List.of("a")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RelevanceBenchmark.assessScreening(sample, List.of("a"), List.of("a", "a")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RelevanceBenchmark.assessScreening(sample, List.of("a"), List.of("new")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(RelevanceBenchmark.assessScreening(sample, List.of(), List.of()).preservesAvailableSafetyEvidence()).isFalse();
    }

    @Test
    void expandedBenchmarkRejectsHeuristicThatDropsAliasAndUncertainConflict() throws Exception {
        try (var input = RelevanceBenchmarkTest.class.getResourceAsStream("/relevance-benchmark-v2.json")) {
            var dataset = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    java.util.Objects.requireNonNull(input), RelevanceBenchmark.Dataset.class);
            int droppedRelevant = 0, droppedUncertain = 0, harmedUncertainPairs = 0;
            for (var sample : dataset.cases()) {
                var candidates = sample.documents().stream().map(RelevanceBenchmark.Document::id).toList();
                var queryEntities = toyEntities(sample.question());
                var retained = sample.documents().stream().filter(doc -> {
                    var documentEntities = toyEntities(doc.text());
                    return queryEntities.isEmpty() || documentEntities.isEmpty()
                            || !java.util.Collections.disjoint(queryEntities, documentEntities);
                }).map(RelevanceBenchmark.Document::id).toList();
                var result = RelevanceBenchmark.assessScreening(sample, candidates, retained);
                droppedRelevant += result.removedRelevant();
                droppedUncertain += result.removedUncertain();
                harmedUncertainPairs += result.harmedUncertainPairs();
                assertThat(result.preexistingIncompletePairs()).isZero();
            }
            assertThat(droppedRelevant).isEqualTo(1);
            assertThat(droppedUncertain).isEqualTo(10);
            assertThat(harmedUncertainPairs).isEqualTo(1);
        }
    }

    @Test
    void recordedModelShadowOutputCannotRemoveUncertainEvidence() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var benchmark = RelevanceBenchmarkTest.class.getResourceAsStream("/relevance-benchmark-v2.json");
             var snapshot = RelevanceBenchmarkTest.class.getResourceAsStream("/relevance-v2-shadow-snapshot.json")) {
            var dataset = json.readValue(java.util.Objects.requireNonNull(benchmark), RelevanceBenchmark.Dataset.class);
            var recorded = json.readTree(java.util.Objects.requireNonNull(snapshot));
            assertThat(recorded.path("datasetVersion").asText()).isEqualTo(dataset.version());
            var cases = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
            recorded.path("cases").forEach(item -> {
                assertThat(cases.put(item.path("caseId").asText(), item.path("labels"))).isNull();
            });
            assertThat(cases.keySet()).containsExactlyInAnyOrderElementsOf(
                    dataset.cases().stream().map(RelevanceBenchmark.Sample::id).toList());
            int relevant = 0, uncertain = 0, irrelevant = 0, failed = 0;
            for (var sample : dataset.cases()) {
                var labels = cases.get(sample.id());
                assertThat(labels.isObject()).isTrue();
                var fieldNames = new java.util.HashSet<String>();
                labels.fieldNames().forEachRemaining(fieldNames::add);
                assertThat(fieldNames).containsExactlyInAnyOrderElementsOf(
                        sample.documents().stream().map(RelevanceBenchmark.Document::id).toList());
                var candidates = sample.documents().stream().map(RelevanceBenchmark.Document::id).toList();
                var retained = sample.documents().stream().filter(doc -> {
                    String label = labels.path(doc.id()).asText();
                    assertThat(label).isIn("RELEVANT", "IRRELEVANT", "UNCERTAIN");
                    return !label.equals("IRRELEVANT");
                }).map(RelevanceBenchmark.Document::id).toList();
                var result = RelevanceBenchmark.assessScreening(sample, candidates, retained);
                relevant += result.removedRelevant(); uncertain += result.removedUncertain();
                irrelevant += result.removedIrrelevant();
                if (!result.preservesAvailableSafetyEvidence()) failed++;
                assertThat(result.harmedRelevantPairs()).isZero();
                assertThat(result.harmedUncertainPairs()).isZero();
            }
            assertThat(relevant).isZero();
            assertThat(uncertain).isEqualTo(3);
            assertThat(irrelevant).isEqualTo(32);
            assertThat(failed).isEqualTo(1);
        }
    }

    @Test
    void earlierModelDocumentFilteringBreaksAmbiguousConflictPairsInV3() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        try (var benchmark=RelevanceBenchmarkTest.class.getResourceAsStream("/relevance-benchmark-v3.json");
             var first=RelevanceBenchmarkTest.class.getResourceAsStream("/relevance-v2-shadow-snapshot.json");
             var second=RelevanceBenchmarkTest.class.getResourceAsStream("/relevance-v2-shadow-second-snapshot.json")) {
            var dataset=json.readValue(java.util.Objects.requireNonNull(benchmark),RelevanceBenchmark.Dataset.class);
            for (var stream : List.of(java.util.Objects.requireNonNull(first),java.util.Objects.requireNonNull(second))) {
                var snapshot=json.readTree(stream);
                var labels=new java.util.HashMap<String,com.fasterxml.jackson.databind.JsonNode>();
                snapshot.path("cases").forEach(item->labels.put(item.path("caseId").asText(),item.path("labels")));
                int harmedPairs=0;
                for (var sample:dataset.cases()) {
                    var all=sample.documents().stream().map(RelevanceBenchmark.Document::id).toList();
                    var retained=all.stream().filter(id->!"IRRELEVANT".equals(
                            labels.get(sample.id()).path(id).asText())).toList();
                    var result=RelevanceBenchmark.assessScreening(sample,all,retained);
                    harmedPairs+=result.harmedUncertainPairs();
                }
                assertThat(harmedPairs).isGreaterThanOrEqualTo(4);
            }
        }
    }
    @Test
    void repeatedShadowConsensusStillFailsTheUncertainEvidenceGate() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var benchmark = RelevanceBenchmarkTest.class.getResourceAsStream("/relevance-benchmark-v2.json");
             var first = RelevanceBenchmarkTest.class.getResourceAsStream("/relevance-v2-shadow-snapshot.json");
             var second = RelevanceBenchmarkTest.class.getResourceAsStream("/relevance-v2-shadow-second-snapshot.json")) {
            var dataset = json.readValue(java.util.Objects.requireNonNull(benchmark), RelevanceBenchmark.Dataset.class);
            var snapshots = List.of(json.readTree(java.util.Objects.requireNonNull(first)),
                    json.readTree(java.util.Objects.requireNonNull(second)));
            var runs = new java.util.ArrayList<java.util.Map<String, com.fasterxml.jackson.databind.JsonNode>>();
            for (var snapshot : snapshots) {
                assertThat(snapshot.path("datasetVersion").asText()).isEqualTo(dataset.version());
                var cases = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
                snapshot.path("cases").forEach(item ->
                        assertThat(cases.put(item.path("caseId").asText(), item.path("labels"))).isNull());
                assertThat(cases.keySet()).containsExactlyInAnyOrderElementsOf(
                        dataset.cases().stream().map(RelevanceBenchmark.Sample::id).toList());
                runs.add(cases);
            }
            int relevant = 0, uncertain = 0, irrelevant = 0, differences = 0;
            for (var sample : dataset.cases()) {
                var one = runs.get(0).get(sample.id()); var two = runs.get(1).get(sample.id());
                var candidates = sample.documents().stream().map(RelevanceBenchmark.Document::id).toList();
                var retained = new java.util.ArrayList<String>();
                for (var id : candidates) {
                    String a = one.path(id).asText(); String b = two.path(id).asText();
                    assertThat(a).isIn("RELEVANT", "IRRELEVANT", "UNCERTAIN");
                    assertThat(b).isIn("RELEVANT", "IRRELEVANT", "UNCERTAIN");
                    if (!a.equals(b)) differences++;
                    if (!a.equals("IRRELEVANT") || !b.equals("IRRELEVANT")) retained.add(id);
                }
                var result = RelevanceBenchmark.assessScreening(sample, candidates, retained);
                relevant += result.removedRelevant(); uncertain += result.removedUncertain();
                irrelevant += result.removedIrrelevant();
            }
            assertThat(differences).isEqualTo(4);
            assertThat(relevant).isZero();
            assertThat(uncertain).isEqualTo(3);
            assertThat(irrelevant).isEqualTo(32);
        }
    }

    private static java.util.Set<String> toyEntities(String text) {
        var result = new java.util.HashSet<String>();
        var matcher = java.util.regex.Pattern.compile("[\\p{IsHan}]{2}(?:项目|系统)").matcher(text);
        while (matcher.find()) result.add(matcher.group());
        return result;
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
