package com.yunjihai.rag.qa.conflict;

import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DeterministicEvidenceConflictDetectorTest {
    private final DeterministicEvidenceConflictDetector detector=
            new DeterministicEvidenceConflictDetector();

    @Test
    void failsClosedWithinBoundedWorkForDenseScreeningCandidates() {
        var dense = java.util.stream.IntStream.range(0, 30).mapToObj(index -> {
            String text = java.util.stream.IntStream.range(0, 50)
                    .mapToObj(fact -> "指标" + index + "项" + fact + "为1天；")
                    .collect(java.util.stream.Collectors.joining());
            return evidence(index + 1, text);
        }).toList();
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> detector.hasConflict(dense))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("CONFLICT_SCREENING_LIMIT_EXCEEDED"));
    }

    @Test
    void failsClosedWhenApprovalFactsExceedTheLimit() {
        var dense = java.util.stream.IntStream.range(0, 30).mapToObj(index -> {
            String text = java.util.stream.IntStream.range(0, 50)
                    .mapToObj(fact -> "方案" + index + "项" + fact + "已批准；")
                    .collect(java.util.stream.Collectors.joining());
            return evidence(index + 1, text);
        }).toList();
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> detector.hasConflict(dense))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("CONFLICT_SCREENING_LIMIT_EXCEEDED"));
    }

    @ParameterizedTest
    @MethodSource("conflictingFacts")
    void detectsConflictingTypedFactsAcrossDifferentEvidence(String left,String right){
        assertThat(detector.hasConflict(List.of(evidence(1,left),evidence(2,right)))).isTrue();
    }

    static Stream<Arguments> conflictingFacts(){
        return Stream.of(
                Arguments.of("项目预算：1万元", "项目预算为2万元"),
                Arguments.of("项目预算设置为1万元", "项目预算：2万元"),
                Arguments.of("项目预算设为1万元", "项目预算为2万元"),
                Arguments.of("项目预算定为1万元", "项目预算：2万元"),
                Arguments.of("服务期限为12个月", "服务期限：24个月"),
                Arguments.of("生效日期：2026年1月1日", "生效日期为2026-02-01"),
                Arguments.of("折扣比例：10%", "折扣比例为20%"),
                Arguments.of("功能状态：启用", "功能状态为停用"));
    }

    @ParameterizedTest
    @MethodSource("factKeysWithUnicodeWhitespace")
    void normalizesUnicodeWhitespaceInsideSemanticFactKeys(String left){
        assertThat(detector.hasConflict(List.of(
                evidence(1,left),
                evidence(2,"服务期限：24个月")))).isTrue();
    }

    static Stream<String> factKeysWithUnicodeWhitespace(){
        return Stream.of(
                "服务 期限：12个月",
                "服务\u3000期限：12个月",
                "服务\u2003期限：12个月");
    }

    @Test
    void comparesSignedAmountsWithoutConfusingLossAndProfit() {
        assertThat(detector.hasConflict(List.of(
                evidence(1,"项目收益：-5万元"),evidence(2,"项目收益：5万元")))).isTrue();
        assertThat(detector.hasConflict(List.of(
                evidence(1,"项目收益：−5万元"),evidence(2,"项目收益：5万元")))).isTrue();
        assertThat(detector.hasConflict(List.of(
                evidence(1,"项目收益：-1万元"),evidence(2,"项目收益：-10000元")))).isFalse();
    }
    @Test
    void preservesLineBoundariesWhenExtractingMultipleFacts(){
        assertThat(detector.hasConflict(List.of(
                evidence(1,"项目预算：1万元\n服务期限：12个月"),
                evidence(2,"项目预算：1万元\n服务期限：24个月")))).isTrue();
    }

    @Test
    void refusesExplicitOppositeApprovalStatesAcrossEvidence() {
        assertThat(detector.hasConflict(List.of(
                evidence(1,"虚构结论 A：该方案已经正式批准并立即生效。"),
                evidence(2,"虚构结论 B：该方案明确未获批准且禁止执行。")))).isTrue();
        assertThat(detector.hasConflict(List.of(
                evidence(1,"项目甲已批准。"), evidence(2,"项目甲尚未批准。")))).isTrue();
        assertThat(detector.hasConflict(List.of(
                evidence(1,"项目甲已被批准。"), evidence(2,"项目甲未被批准。")))).isTrue();
        assertThat(detector.hasConflict(List.of(
                evidence(1,"项目甲予以批准。"), evidence(2,"项目甲不予批准。")))).isTrue();
    }

    @Test
    void doesNotTreatDifferentSubjectsOrSameApprovalStatusAsConflicts() {
        assertThat(detector.hasConflict(List.of(
                evidence(1,"项目甲已批准。"), evidence(2,"项目乙未获批准。")))).isFalse();
        assertThat(detector.hasConflict(List.of(
                evidence(1,"项目甲尚未批准。"), evidence(2,"项目甲未获批准。")))).isFalse();
        assertThat(detector.hasConflict(List.of(evidence(1,"项目甲已批准。项目甲未获批准。")))).isFalse();
    }

    @ParameterizedTest
    @MethodSource("equivalentFacts")
    void treatsEquivalentUnitsAndStatesAsTheSameValue(String left,String right){
        assertThat(detector.hasConflict(List.of(evidence(1,left),evidence(2,right)))).isFalse();
    }

    static Stream<Arguments> equivalentFacts(){
        return Stream.of(
                Arguments.of("项目预算：1万元", "项目预算为10000元"),
                Arguments.of("服务期限：1年", "服务期限为12个月"),
                Arguments.of("存储容量：1GB", "存储容量为1024MB"),
                Arguments.of("功能状态：启用", "功能状态为开启"));
    }

    @Test
    void doesNotEraseSmallNumericDifferencesWhenComparingFacts() {
        assertThat(detector.hasConflict(List.of(
                evidence(1,"配额数值：0.0000000000001元"),
                evidence(2,"配额数值：0.0000000000002元")))).isTrue();
        assertThat(detector.hasConflict(List.of(
                evidence(1,"配额数值：0.0000000000001万元"),
                evidence(2,"配额数值：0.000000001元")))).isFalse();
    }
    @Test
    void matchesAQualifiedFactKeyToItsMeaningfulSuffix(){
        assertThat(detector.hasConflict(List.of(
                evidence(1,"公司差旅报销期限：30天"),
                evidence(2,"报销期限为60日")))).isTrue();
    }

    @Test
    void doesNotConfuseDifferentFactsThatShareOnlyAShortSuffix(){
        assertThat(detector.hasConflict(List.of(
                evidence(1,"报销期限：30天"),
                evidence(2,"付款期限：60天")))).isFalse();
    }

    @Test
    void doesNotCompareDifferentValuesInsideOneEvidenceItem(){
        assertThat(detector.hasConflict(List.of(
                evidence(1,"试用期限：30天。正式期限：60天。")))).isFalse();
    }

    @Test
    void normalizesUnicodeWhitespaceForDuplicateIdentityAndRejectsRealDivergence(){
        Evidence canonical=evidence(1,"报销期限：30 天");
        Evidence equivalent=new Evidence(
                canonical.spaceId(),id(2),canonical.documentId(),canonical.versionId(),canonical.nodeId(),
                canonical.title(),"报销期限: 30天",canonical.score(),canonical.locator());
        Evidence divergent=new Evidence(
                canonical.spaceId(),id(3),canonical.documentId(),canonical.versionId(),canonical.nodeId(),
                canonical.title(),"报销期限：60天",canonical.score(),canonical.locator());

        assertThat(detector.hasConflict(List.of(canonical,equivalent))).isFalse();
        assertThat(detector.hasConflict(List.of(canonical,divergent))).isTrue();
    }

    @Test
    void detectsConflictingExplicitBackupRetentionWithoutCopula() {
        assertThat(detector.hasConflict(List.of(
                evidence(1, "北辰平台生产环境备份保留30天。"),
                evidence(2, "北辰平台生产环境备份保留90天。")))).isTrue();
    }

    @Test
    void reportsOnlyConflictCategoryWithoutExposingFacts() {
        assertThat(detector.conflictKind(List.of(evidence(1,"项目预算：1万元"), evidence(2,"项目预算：2万元"))))
                .isEqualTo(EvidenceConflictDetector.ConflictKind.TYPED_FACT);
        assertThat(detector.conflictKind(List.of(evidence(1,"项目预算：1万元"), evidence(2,"项目预算：1万元"))))
                .isEqualTo(EvidenceConflictDetector.ConflictKind.NONE);
        assertThat(detector.conflictKind(List.of(evidence(1,"该方案已批准。"), evidence(2,"该方案未批准。"))))
                .isEqualTo(EvidenceConflictDetector.ConflictKind.APPROVAL);
        EvidenceConflictDetector custom = ignored -> true;
        assertThat(custom.conflictKind(List.of())).isEqualTo(EvidenceConflictDetector.ConflictKind.OTHER);
    }

    @Test
    void reportsEveryConflictingPairWithoutExposingTheirContents() {
        var first=evidence(1,"星河项目预算：100万元。该方案已批准。");
        var second=evidence(2,"星河项目预算：120万元。该方案未批准。");
        var third=evidence(3,"星河项目预算：130万元。");
        var findings=detector.findConflictingPairs(List.of(first,second,third));
        assertThat(findings).extracting(f->f.kind()).contains(
                EvidenceConflictDetector.ConflictKind.APPROVAL, EvidenceConflictDetector.ConflictKind.TYPED_FACT);
        assertThat(findings).anySatisfy(f->{
            assertThat(f.firstEvidenceId()).isEqualTo(first.evidenceId());
            assertThat(f.secondEvidenceId()).isEqualTo(second.evidenceId());
            assertThat(f.kind()).isEqualTo(EvidenceConflictDetector.ConflictKind.APPROVAL);
        });
        assertThat(findings).anySatisfy(f->{
            assertThat(f.firstEvidenceId()).isEqualTo(first.evidenceId());
            assertThat(f.secondEvidenceId()).isEqualTo(third.evidenceId());
            assertThat(f.kind()).isEqualTo(EvidenceConflictDetector.ConflictKind.TYPED_FACT);
        });
        assertThat(findings.toString()).doesNotContain("星河项目","100万元","120万元");
    }
    @Test
    void pairDiagnosticsDoNotHideLaterUninterpretableTableUnits() {
        var detector=new DeterministicEvidenceConflictDetector(item -> budgetCells("星河项目",
                item.evidenceId().equals(id(3)) ? "金额（天）" : "金额（万元）",
                item.evidenceId().equals(id(1)) ? "100" : "120"));
        assertThatThrownBy(() -> detector.findConflictingPairs(
                List.of(tableEvidence(1),tableEvidence(2),tableEvidence(3))))
                .isInstanceOf(IllegalStateException.class).hasMessage("AMBIGUOUS_TABLE_UNITS");
    }

    @Test
    void pairDiagnosticsFailClosedWhenCandidateSetExceedsTheBound() {
        var items=java.util.stream.IntStream.range(0,129).mapToObj(i->evidence(i+1,"内容")).toList();
        assertThatThrownBy(() -> detector.findConflictingPairs(items))
                .isInstanceOf(IllegalStateException.class).hasMessage("CONFLICT_SCREENING_LIMIT_EXCEEDED");
    }
    @Test
    void pairDiagnosticsRemainBoundedForThirtyDenseCandidates() {
        var items=java.util.stream.IntStream.range(0,30).mapToObj(index -> {
            String text=java.util.stream.IntStream.range(0,20)
                    .mapToObj(fact -> "指标"+index+"项"+fact+"为1天；")
                    .collect(java.util.stream.Collectors.joining());
            return evidence(index+1,text);
        }).toList();
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                () -> assertThat(detector.findConflictingPairs(items)).isEmpty());
    }
    @Test
    void refusesBackupRetentionConflictWhenOneSourceDoesNotNameTheProject() {
        assertThat(detector.hasConflict(List.of(
                evidence(1,"备份制度规定星河项目备份保留30天。"),
                evidence(2,"备份保留90天，未注明项目。")))).isTrue();
        assertThat(detector.hasConflict(List.of(
                evidence(1,"星河项目备份保留期限为30日。"),
                evidence(2,"备份保留90天，未注明项目。")))).isTrue();
        assertThat(detector.hasConflict(List.of(
                evidence(1,"星河项目备份保留期限为30日。"),
                evidence(2,"备份保留30天，未注明项目。")))).isFalse();
    }
    @Test
    void backupRetentionKeepsEquivalentUnitsAndDifferentEnvironmentsSeparate() {
        assertThat(detector.hasConflict(List.of(
                evidence(1, "北辰平台生产环境备份保留30天。"),
                evidence(2, "北辰平台生产环境备份保留30日。")))).isFalse();
        assertThat(detector.hasConflict(List.of(
                evidence(1, "北辰平台生产环境备份保留30天。"),
                evidence(2, "北辰平台测试环境备份保留90天。")))).isFalse();
    }

    @Test
    void detectsConflictingBudgetValuesInFlattenedSpreadsheetEvidence() {
        var left = new Evidence(id(100), id(1), id(201), id(301), "table-1", "预算甲",
                "项目\n预算类别\n金额（万元）\n星河项目\n研发预算\n100", 0.9,
                new com.yunjihai.rag.retrieval.domain.TableLocator("预算甲", 1, 2, 1, 3));
        var right = new Evidence(id(100), id(2), id(202), id(302), "table-2", "预算乙",
                "项目\n预算类别\n金额（万元）\n星河项目\n研发预算\n120", 0.9,
                new com.yunjihai.rag.retrieval.domain.TableLocator("预算乙", 1, 2, 1, 3));
        var structured = new DeterministicEvidenceConflictDetector(item -> List.of(
                new com.yunjihai.rag.ingestion.CanonicalTableCell(0,0,1,1,"项目"),
                new com.yunjihai.rag.ingestion.CanonicalTableCell(0,1,1,1,"预算类别"),
                new com.yunjihai.rag.ingestion.CanonicalTableCell(0,2,1,1,"金额（万元）"),
                new com.yunjihai.rag.ingestion.CanonicalTableCell(1,0,1,1,"星河项目"),
                new com.yunjihai.rag.ingestion.CanonicalTableCell(1,1,1,1,"研发预算"),
                new com.yunjihai.rag.ingestion.CanonicalTableCell(1,2,1,1,item.evidenceId().equals(id(1)) ? "100" : "120")));
        assertThat(structured.hasConflict(List.of(left, right))).isTrue();
    }

    @Test
    void comparesEveryTableFactAcrossEvidenceEvenWhenOneEvidenceHasDuplicateKeys() {
        var left=tableEvidence(1); var right=tableEvidence(2);
        var structured=new DeterministicEvidenceConflictDetector(item -> {
            var cells=new java.util.ArrayList<>(budgetCells("星河项目","金额（万元）","100"));
            if (item.evidenceId().equals(id(1))) {
                cells.add(new com.yunjihai.rag.ingestion.CanonicalTableCell(2,0,1,1,"星河项目"));
                cells.add(new com.yunjihai.rag.ingestion.CanonicalTableCell(2,1,1,1,"研发预算"));
                cells.add(new com.yunjihai.rag.ingestion.CanonicalTableCell(2,2,1,1,"120"));
            }
            return cells;
        });
        assertThat(structured.conflictKind(List.of(left,right)))
                .isEqualTo(EvidenceConflictDetector.ConflictKind.STRUCTURED_TABLE);
        assertThat(structured.conflictKind(List.of(right,left)))
                .isEqualTo(EvidenceConflictDetector.ConflictKind.STRUCTURED_TABLE);
    }
    @Test
    void duplicateValuesWithinOneTableDoNotByThemselvesCreateACrossEvidenceConflict() {
        var detector=new DeterministicEvidenceConflictDetector(item -> {
            var cells=new java.util.ArrayList<>(budgetCells(item.evidenceId().equals(id(1))
                    ? "星河项目" : "云舟项目","金额（万元）","100"));
            if (item.evidenceId().equals(id(1))) {
                cells.add(new com.yunjihai.rag.ingestion.CanonicalTableCell(2,0,1,1,"星河项目"));
                cells.add(new com.yunjihai.rag.ingestion.CanonicalTableCell(2,1,1,1,"研发预算"));
                cells.add(new com.yunjihai.rag.ingestion.CanonicalTableCell(2,2,1,1,"120"));
            }
            return cells;
        });
        assertThat(detector.hasConflict(List.of(tableEvidence(1),tableEvidence(2)))).isFalse();
    }

    @Test
    void repeatedTableFactsAreCountedTowardTheScreeningLimit() {
        var detector=new DeterministicEvidenceConflictDetector(item -> {
            var cells=new java.util.ArrayList<>(budgetCells("星河项目","金额（万元）","100"));
            if (item.evidenceId().equals(id(1))) {
                for (int row=2; row<=1025; row++) {
                    cells.add(new com.yunjihai.rag.ingestion.CanonicalTableCell(row,0,1,1,"星河项目"));
                    cells.add(new com.yunjihai.rag.ingestion.CanonicalTableCell(row,1,1,1,"研发预算"));
                    cells.add(new com.yunjihai.rag.ingestion.CanonicalTableCell(row,2,1,1,"100"));
                }
            }
            return cells;
        });
        assertThatThrownBy(() -> detector.hasConflict(List.of(tableEvidence(1),tableEvidence(2))))
                .isInstanceOf(IllegalStateException.class).hasMessage("CONFLICT_SCREENING_LIMIT_EXCEEDED");
    }
    @Test
    void structuredTableFactsRetainFullNumericPrecision() {
        var structured=new DeterministicEvidenceConflictDetector(item -> budgetCells(
                "星河项目","金额（元）",item.evidenceId().equals(id(1))
                        ? "0.0000000000001" : "0.0000000000002"));
        assertThat(structured.conflictKind(List.of(tableEvidence(1),tableEvidence(2))))
                .isEqualTo(EvidenceConflictDetector.ConflictKind.STRUCTURED_TABLE);
    }
    @Test
    void detectsSameDimensionConflictRegardlessOfColumnOrder() {
        var left=tableEvidence(1); var right=tableEvidence(2);
        var structured=new DeterministicEvidenceConflictDetector(item -> {
            var cells=budgetCells("星河项目","金额（万元）",item.evidenceId().equals(id(1)) ? "100" : "120");
            if (item.evidenceId().equals(id(1))) return cells;
            return cells.stream().map(cell -> new com.yunjihai.rag.ingestion.CanonicalTableCell(
                    cell.row(),cell.column()==0 ? 1 : cell.column()==1 ? 0 : cell.column(),
                    cell.rowSpan(),cell.columnSpan(),cell.text())).toList();
        });
        assertThat(structured.conflictKind(List.of(left,right)))
                .isEqualTo(EvidenceConflictDetector.ConflictKind.STRUCTURED_TABLE);
        assertThat(structured.conflictKind(List.of(right,left)))
                .isEqualTo(EvidenceConflictDetector.ConflictKind.STRUCTURED_TABLE);
    }
    @Test
    void reorderedColumnsPreserveEquivalentValuesAndDifferentDimensions() {
        for (boolean differentProject : List.of(false,true)) {
            var structured=new DeterministicEvidenceConflictDetector(item -> {
                boolean second=item.evidenceId().equals(id(2));
                var cells=budgetCells(second && differentProject ? "云舟项目" : "星河项目",
                        second ? "金额（元）" : "金额（万元）", second ? (differentProject ? "1200000" : "1000000") : "100");
                if (!second) return cells;
                return cells.stream().map(cell -> new com.yunjihai.rag.ingestion.CanonicalTableCell(
                        cell.row(),cell.column()==0 ? 1 : cell.column()==1 ? 0 : cell.column(),
                        cell.rowSpan(),cell.columnSpan(),cell.text())).toList();
            });
            assertThat(structured.hasConflict(List.of(tableEvidence(1),tableEvidence(2)))).isFalse();
        }
    }
    @Test
    void comparesSignedTableAmountsAndPreservesEquivalentUnits() {
        var conflicting = new DeterministicEvidenceConflictDetector(item -> budgetCells("星河项目","金额（万元）",
                item.evidenceId().equals(id(1)) ? "-5" : "5"));
        assertThat(conflicting.hasConflict(List.of(tableEvidence(1),tableEvidence(2)))).isTrue();
        var equivalent = new DeterministicEvidenceConflictDetector(item -> budgetCells("星河项目",
                item.evidenceId().equals(id(1)) ? "金额（万元）" : "金额（元）",
                item.evidenceId().equals(id(1)) ? "−1" : "-10000"));
        assertThat(equivalent.hasConflict(List.of(tableEvidence(1),tableEvidence(2)))).isFalse();
    }
    @Test
    void tableFactsRespectDimensionsAndEquivalentCurrencyUnits() {
        var left = tableEvidence(1); var right = tableEvidence(2);
        var same = new DeterministicEvidenceConflictDetector(item -> budgetCells(
                "星河项目", item.evidenceId().equals(id(1)) ? "金额（万元）" : "金额（元）",
                item.evidenceId().equals(id(1)) ? "100" : "1000000"));
        assertThat(same.hasConflict(List.of(left, right))).isFalse();
        var differentProject = new DeterministicEvidenceConflictDetector(item -> budgetCells(
                item.evidenceId().equals(id(1)) ? "星河项目" : "云舟项目", "金额（万元）",
                item.evidenceId().equals(id(1)) ? "100" : "120"));
        assertThat(differentProject.hasConflict(List.of(left, right))).isFalse();
    }

    @Test
    void unavailableOrAmbiguousNumericTableMustFailClosed() {
        var left = tableEvidence(1); var right = tableEvidence(2);
        var unavailable = new DeterministicEvidenceConflictDetector(item -> { throw new IllegalStateException("unavailable"); });
        assertThatThrownBy(() -> unavailable.hasConflict(List.of(left, right))).isInstanceOf(IllegalStateException.class);
        var merged = new DeterministicEvidenceConflictDetector(item -> List.of(
                new com.yunjihai.rag.ingestion.CanonicalTableCell(0,0,1,2,"预算100万元")));
        assertThatThrownBy(() -> merged.hasConflict(List.of(left, right))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mergedTableWithMultilineNumericTextMustFailClosed() {
        for (String text : List.of("预算\n100万元\n", "预算\n１００万元\n")) {
            var merged=new DeterministicEvidenceConflictDetector(item -> List.of(
                    new com.yunjihai.rag.ingestion.CanonicalTableCell(0,0,1,2,text)));
            assertThatThrownBy(() -> merged.hasConflict(List.of(tableEvidence(1),tableEvidence(2))))
                    .isInstanceOf(IllegalStateException.class).hasMessage("AMBIGUOUS_TABLE_EVIDENCE");
        }
    }
    @Test
    void inconsistentUnitsOrNonNumericValuesDoNotSilentlyPassScreening() {
        var left = tableEvidence(1); var right = tableEvidence(2);
        var inconsistentUnits = new DeterministicEvidenceConflictDetector(item -> budgetCells("星河项目",
                item.evidenceId().equals(id(1)) ? "金额（元）" : "金额（天）", "100"));
        assertThatThrownBy(() -> inconsistentUnits.hasConflict(List.of(left, right)))
                .isInstanceOf(IllegalStateException.class);
        var missing = new DeterministicEvidenceConflictDetector(item -> budgetCells("星河项目", "金额（万元）", "未注明"));
        assertThatThrownBy(() -> missing.hasConflict(List.of(left, right)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Evidence tableEvidence(int n) {
        return new Evidence(id(100),id(n),id(200+n),id(300+n),"table-"+n,"表格",
                "虚构预算信息",0.9,new com.yunjihai.rag.retrieval.domain.TableLocator("表",1,2,1,3));
    }
    private static List<com.yunjihai.rag.ingestion.CanonicalTableCell> budgetCells(String project,String header,String amount) {
        return List.of(new com.yunjihai.rag.ingestion.CanonicalTableCell(0,0,1,1,"项目"),
                new com.yunjihai.rag.ingestion.CanonicalTableCell(0,1,1,1,"预算类别"),
                new com.yunjihai.rag.ingestion.CanonicalTableCell(0,2,1,1,header),
                new com.yunjihai.rag.ingestion.CanonicalTableCell(1,0,1,1,project),
                new com.yunjihai.rag.ingestion.CanonicalTableCell(1,1,1,1,"研发预算"),
                new com.yunjihai.rag.ingestion.CanonicalTableCell(1,2,1,1,amount));
    }

    private static Evidence evidence(int suffix,String excerpt){
        return new Evidence(
                id(100),id(suffix),id(200+suffix),id(300+suffix),"node-"+suffix,
                "制度",excerpt,0.9,new MarkdownLocator(List.of("第一章"),"node-"+suffix));
    }

    private static UUID id(int suffix){
        return UUID.fromString("00000000-0000-7000-8000-%012d".formatted(suffix));
    }
}
