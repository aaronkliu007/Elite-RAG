package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.qa.conflict.DeterministicEvidenceConflictDetector.ConflictPair;
import com.yunjihai.rag.qa.conflict.EvidenceConflictDetector.ConflictKind;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionScopedConflictShadowTest {
    @Test
    void explicitIndependentTopicsAreShadowOnlyAndEveryAmbiguityStaysUnknown() {
        var a=evidence(1,"星河项目研发预算为100万元。");
        var b=evidence(2,"星河项目研发预算为120万元。");
        var pair=new ConflictPair(a.evidenceId(),b.evidenceId(),ConflictKind.TYPED_FACT);
        var sources=Map.of(a.evidenceId(),a,b.evidenceId(),b);
        assertThat(QuestionScopedConflictShadow.assess("星河项目的备份保留多久？",pair,sources))
                .isEqualTo(QuestionScopedConflictShadow.Scope.EXPLICITLY_UNRELATED);
        assertThat(QuestionScopedConflictShadow.assess("星河项目预算和备份保留多久？",pair,sources))
                .isEqualTo(QuestionScopedConflictShadow.Scope.UNKNOWN);
        assertThat(QuestionScopedConflictShadow.assess("星河项目预算是多少？",pair,sources))
                .isEqualTo(QuestionScopedConflictShadow.Scope.UNKNOWN);
        assertThat(QuestionScopedConflictShadow.assess("星河项目的备份保留多久？",pair,Map.of(a.evidenceId(),a)))
                .isEqualTo(QuestionScopedConflictShadow.Scope.UNKNOWN);

        var unnamed=evidence(3,"研发预算为150万元，未说明项目。");
        var uncertainPair=new ConflictPair(a.evidenceId(),unnamed.evidenceId(),ConflictKind.TYPED_FACT);
        assertThat(QuestionScopedConflictShadow.assess("星河项目的备份保留多久？",uncertainPair,
                Map.of(a.evidenceId(),a,unnamed.evidenceId(),unnamed)))
                .isEqualTo(QuestionScopedConflictShadow.Scope.UNKNOWN);
        var mixed=evidence(4,"星河项目研发预算为120万元，备份保留90天。");
        assertThat(QuestionScopedConflictShadow.assess("星河项目的备份保留多久？",
                new ConflictPair(a.evidenceId(),mixed.evidenceId(),ConflictKind.TYPED_FACT),
                Map.of(a.evidenceId(),a,mixed.evidenceId(),mixed)))
                .isEqualTo(QuestionScopedConflictShadow.Scope.UNKNOWN);
    }

    @Test
    void unrelatedBudgetPairDoesNotMaskAnAmbiguousBackupConflict() {
        var a=evidence(1,"星河项目研发预算为100万元。");
        var b=evidence(2,"星河项目研发预算为120万元。");
        var backup=evidence(3,"星河项目备份保留30天。");
        var unknown=evidence(4,"备份保留90天，未注明项目。");
        var detector=new com.yunjihai.rag.qa.conflict.DeterministicEvidenceConflictDetector();
        var clean=List.of(a,b,backup);
        var cleanMap=clean.stream().collect(java.util.stream.Collectors.toMap(Evidence::evidenceId,e->e));
        var cleanFindings=detector.findConflictingPairs(clean);
        assertThat(cleanFindings).isNotEmpty().allSatisfy(pair -> assertThat(
                QuestionScopedConflictShadow.assess("星河项目的备份保留多久？",pair,cleanMap))
                .isEqualTo(QuestionScopedConflictShadow.Scope.EXPLICITLY_UNRELATED));

        var ambiguous=List.of(a,b,backup,unknown);
        var ambiguousMap=ambiguous.stream().collect(java.util.stream.Collectors.toMap(Evidence::evidenceId,e->e));
        assertThat(detector.findConflictingPairs(ambiguous)).anySatisfy(pair -> assertThat(
                QuestionScopedConflictShadow.assess("星河项目的备份保留多久？",pair,ambiguousMap))
                .isEqualTo(QuestionScopedConflictShadow.Scope.UNKNOWN));
    }
    @Test
    void simpleBudgetTablesCanBeIdentifiedAsUnrelatedWithoutHidingUnknownTables() {
        var a=tableEvidence(11); var b=tableEvidence(12);
        var pair=new ConflictPair(a.evidenceId(),b.evidenceId(),ConflictKind.STRUCTURED_TABLE);
        var sources=Map.of(a.evidenceId(),a,b.evidenceId(),b);
        com.yunjihai.rag.qa.conflict.TableEvidenceSource valid=item -> budgetCells(
                "星河项目",item.evidenceId().equals(a.evidenceId()) ? "100" : "120");
        assertThat(QuestionScopedConflictShadow.assess("星河项目的备份保留多久？",pair,sources,valid))
                .isEqualTo(QuestionScopedConflictShadow.Scope.EXPLICITLY_UNRELATED);
        assertThat(QuestionScopedConflictShadow.assess("星河项目预算和备份保留多久？",pair,sources,valid))
                .isEqualTo(QuestionScopedConflictShadow.Scope.UNKNOWN);
        assertThat(QuestionScopedConflictShadow.assess("星河项目的备份保留多久？",pair,sources,
                item -> budgetCells("", "100"))).isEqualTo(QuestionScopedConflictShadow.Scope.UNKNOWN);
        assertThat(QuestionScopedConflictShadow.assess("星河项目的备份保留多久？",pair,sources,
                item -> { throw new IllegalStateException("unavailable"); }))
                .isEqualTo(QuestionScopedConflictShadow.Scope.UNKNOWN);
        var mixed=new java.util.ArrayList<>(budgetCells("星河项目","100"));
        mixed.add(new com.yunjihai.rag.ingestion.CanonicalTableCell(2,0,1,1,"星河项目"));
        mixed.add(new com.yunjihai.rag.ingestion.CanonicalTableCell(2,1,1,1,"备份期限"));
        mixed.add(new com.yunjihai.rag.ingestion.CanonicalTableCell(2,2,1,1,"90"));
        assertThat(QuestionScopedConflictShadow.assess("星河项目的备份保留多久？",pair,sources,item -> mixed))
                .isEqualTo(QuestionScopedConflictShadow.Scope.UNKNOWN);
    }

    @Test
    void realisticFourColumnBudgetTablesDoNotAnswerBackupAndRestoreQuestions() {
        var first=tableEvidence(41); var second=tableEvidence(42);
        var sources=Map.of(first.evidenceId(),first,second.evidenceId(),second);
        com.yunjihai.rag.qa.conflict.TableEvidenceSource tables=item -> realisticBudgetCells(
                item.evidenceId().equals(first.evidenceId()) ? "100" : "120", "未注明");
        var detector=new com.yunjihai.rag.qa.conflict.DeterministicEvidenceConflictDetector(tables);
        var findings=detector.findConflictingPairs(List.of(first,second));
        assertThat(findings).anySatisfy(pair -> {
            assertThat(pair.kind()).isEqualTo(ConflictKind.STRUCTURED_TABLE);
            assertThat(QuestionScopedConflictShadow.assess("星河项目的备份保留多久？",pair,sources,tables))
                    .isEqualTo(QuestionScopedConflictShadow.Scope.EXPLICITLY_UNRELATED);
            assertThat(QuestionScopedConflictShadow.assess("星河项目多久组织一次恢复演练？",pair,sources,tables))
                    .isEqualTo(QuestionScopedConflictShadow.Scope.EXPLICITLY_UNRELATED);
            assertThat(QuestionScopedConflictShadow.assess("星河项目研发预算究竟是多少？",pair,sources,tables))
                    .isEqualTo(QuestionScopedConflictShadow.Scope.UNKNOWN);
            assertThat(QuestionScopedConflictShadow.assess("星河项目的备份保留多久？",pair,sources,
                    item -> realisticBudgetCells("100","备份保留90天")))
                    .isEqualTo(QuestionScopedConflictShadow.Scope.UNKNOWN);
        });
    }

    private static List<com.yunjihai.rag.ingestion.CanonicalTableCell> realisticBudgetCells(String amount,String dateAndVersion) {
        var cells=new java.util.ArrayList<>(budgetCells("星河项目",amount));
        cells.add(new com.yunjihai.rag.ingestion.CanonicalTableCell(0,3,1,1,"审批日期及版本"));
        cells.add(new com.yunjihai.rag.ingestion.CanonicalTableCell(1,3,1,1,dateAndVersion));
        return cells;
    }

    @Test
    void replaysTheActuallySelectedBudgetTablesFromHistoricalSynthetic42Snapshot() throws Exception {
        var source=QuestionScopedConflictShadowTest.class.getResourceAsStream("/synthetic42-selected-conflicts.json");
        var fixture=new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.util.Objects.requireNonNull(source));
        var first=tableEvidence(41); var second=tableEvidence(42);
        var evidence=Map.of(first.evidenceId(),first,second.evidenceId(),second);
        com.yunjihai.rag.qa.conflict.TableEvidenceSource tables=item -> realisticBudgetCells(
                item.evidenceId().equals(first.evidenceId()) ? "100" : "120","未注明");
        var pair=new ConflictPair(first.evidenceId(),second.evidenceId(),ConflictKind.STRUCTURED_TABLE);
        int completePairs=0; var explicitlyUnrelated=new java.util.TreeSet<String>();
        for (var item:fixture.path("cases")) {
            var titles=new java.util.HashSet<String>(); item.path("selectedBudgetTables").forEach(v->titles.add(v.asText()));
            if (!titles.containsAll(Set.of("05_星河项目预算记录甲","06_星河项目预算记录乙"))) continue;
            completePairs++;
            if (QuestionScopedConflictShadow.assess(item.path("question").asText(),pair,evidence,tables)
                    ==QuestionScopedConflictShadow.Scope.EXPLICITLY_UNRELATED) explicitlyUnrelated.add(item.path("id").asText());
        }
        assertThat(fixture.path("cases")).hasSize(15);
        assertThat(completePairs).isEqualTo(11);
        assertThat(explicitlyUnrelated).containsExactly("Q05","Q06");
        // Selected evidence is not the complete conflict-screening candidate set; this cannot approve answers.
    }

    @Test
    void historicalDocumentHitDoesNotGuaranteeThatTheSelectedExcerptContainsTheAnswer() throws Exception {
        try (var source=QuestionScopedConflictShadowTest.class.getResourceAsStream("/synthetic42-answer-span-snapshot.json")) {
            var fixture=new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.util.Objects.requireNonNull(source));
            String title=fixture.path("referenceTitle").asText();
            var cases=fixture.path("cases");
            assertThat(cases).hasSize(2);
            for (var item:cases) {
                var required=new java.util.ArrayList<String>(); item.path("requiredFragments").forEach(v->required.add(v.asText()));
                boolean baselineDoc=false,baselineSpan=false,rerankedSpan=false;
                for (var entry:item.path("baseline")) {
                    if (title.equals(entry.path("title").asText())) {
                        baselineDoc=true;
                        String text=entry.path("excerpt").asText();
                        baselineSpan|=required.stream().allMatch(text::contains);
                    }
                }
                for (var entry:item.path("localRerank")) {
                    String text=entry.path("excerpt").asText();
                    if (title.equals(entry.path("title").asText()))
                        rerankedSpan|=required.stream().allMatch(text::contains);
                }
                assertThat(baselineDoc).isTrue();
                assertThat(baselineSpan).isEqualTo(item.path("id").asText().equals("Q06"));
                assertThat(rerankedSpan).isTrue();
            }
        }
    }

    @Test
    void exactQuoteAloneDoesNotProveThatAnAnswerAddressesTheQuestion() {
        var id=new UUID(0,31);
        String budget="星河项目研发预算为120万元。";
        var source=new com.yunjihai.rag.qa.domain.QaEvidence(id,new UUID(0,99),new UUID(1,31),
                new UUID(2,31),"node-31","预算资料",budget,1.0,new MarkdownLocator(List.of("章节"),"node-31"));
        var support=new com.yunjihai.rag.qa.domain.EvidenceSupport(id,budget);
        var claim=new com.yunjihai.rag.qa.domain.GroundedClaim(new UUID(0,32),budget,List.of(support));
        var candidate=new com.yunjihai.rag.qa.domain.CandidateAnswer(
                com.yunjihai.rag.qa.domain.CandidateAnswer.Decision.ANSWER,budget,List.of(claim));
        var bundle=new com.yunjihai.rag.qa.domain.EvidenceBundle(List.of(source),budget.length());
        // This verifier has no question input: a true result cannot establish relevance to a backup question.
        assertThat(new com.yunjihai.rag.qa.ExactQuoteCitationSupportVerifier().isSupported(candidate,bundle)).isTrue();
    }

    private static Evidence tableEvidence(int id) {
        UUID evidenceId=new UUID(0,id);
        return new Evidence(new UUID(0,99),evidenceId,new UUID(1,id),new UUID(2,id),"node-"+id,
                "虚构预算", "预算表",1.0,
                new com.yunjihai.rag.retrieval.domain.TableLocator("预算表",1,2,1,3));
    }
    private static List<com.yunjihai.rag.ingestion.CanonicalTableCell> budgetCells(String project,String amount) {
        return List.of(new com.yunjihai.rag.ingestion.CanonicalTableCell(0,0,1,1,"项目"),
                new com.yunjihai.rag.ingestion.CanonicalTableCell(0,1,1,1,"预算类别"),
                new com.yunjihai.rag.ingestion.CanonicalTableCell(0,2,1,1,"金额（万元）"),
                new com.yunjihai.rag.ingestion.CanonicalTableCell(1,0,1,1,project),
                new com.yunjihai.rag.ingestion.CanonicalTableCell(1,1,1,1,"研发预算"),
                new com.yunjihai.rag.ingestion.CanonicalTableCell(1,2,1,1,amount));
    }
    private static Evidence evidence(int id,String text) {
        UUID evidenceId=new UUID(0,id);
        return new Evidence(new UUID(0,99),evidenceId,new UUID(1,id),new UUID(2,id),"node-"+id,
                "虚构资料",text,1.0,new MarkdownLocator(List.of("章节"),"node-"+id));
    }
}