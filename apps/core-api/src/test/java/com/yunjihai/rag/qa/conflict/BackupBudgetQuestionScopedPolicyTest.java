package com.yunjihai.rag.qa.conflict;

import com.yunjihai.rag.ingestion.CanonicalTableCell;
import com.yunjihai.rag.qa.domain.*;
import com.yunjihai.rag.retrieval.domain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class BackupBudgetQuestionScopedPolicyTest {
    private static final UUID SPACE=UUID.randomUUID();
    private static Evidence table(String amount) {
        return new Evidence(SPACE,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),amount,
                "预算", "研发预算",1,new TableLocator("预算",1,2,1,3));
    }
    private static List<CanonicalTableCell> budget(String amount) {
        return List.of(new CanonicalTableCell(0,0,1,1,"项目"),new CanonicalTableCell(0,1,1,1,"预算类别"),
                new CanonicalTableCell(0,2,1,1,"金额（万元）"),new CanonicalTableCell(1,0,1,1,"星河项目"),
                new CanonicalTableCell(1,1,1,1,"研发预算"),new CanonicalTableCell(1,2,1,1,amount));
    }
    private static TableEvidenceSource provenTables() {
        return new TableEvidenceSource() {
            @Override public List<CanonicalTableCell> load(Evidence item) { return budget(item.nodeId()); }
            @Override public boolean hasStandaloneBudgetContext(Evidence item) { return true; }
            @Override public boolean hasStandaloneBackupContext(Evidence item) { return true; }
        };
    }
    @Test void recognizesOnlyNarrowIndependentConflictAndRejectsUnknown() {
        var a=table("100"); var b=table("120");
        var pair=new DeterministicEvidenceConflictDetector.ConflictPair(a.evidenceId(),b.evidenceId(),EvidenceConflictDetector.ConflictKind.STRUCTURED_TABLE);
        var policy=new BackupBudgetQuestionScopedPolicy(provenTables());
        assertThat(policy.version()).isEqualTo("backup-budget-v2");
        var backup=new Evidence(SPACE,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"backup","制度",
                "星河项目备份保留30天。",1,new MarkdownLocator(List.of("章节"),"backup"));
        assertThat(policy.classify(QaQuestion.normalize("星河项目的备份保留多久？"),pair,List.of(a,b,backup)))
                .isEqualTo(QuestionScopedConflictPolicy.Scope.UNRELATED);
        for(var question:List.of("星河项目预算是多少？","星河项目的预算和备份保留多久？","星河项目删除的备份保留多久？"))
            assertThat(policy.classify(QaQuestion.normalize(question),pair,List.of(a,b))).isEqualTo(QuestionScopedConflictPolicy.Scope.UNKNOWN);
        assertThat(new BackupBudgetQuestionScopedPolicy(item -> {throw new IllegalStateException();})
                .classify(QaQuestion.normalize("星河项目的备份保留多久？"),pair,List.of(a,b)))
                .isEqualTo(QuestionScopedConflictPolicy.Scope.UNKNOWN);
    }
    @Test void verifiesTheExactQuestionBoundAnswerAndRejectsOtherOrQualifiedSources() {
        UUID id=UUID.randomUUID(); String text="星河项目备份保留30天。";
        var item=new QaEvidence(id,SPACE,UUID.randomUUID(),UUID.randomUUID(),"n","制度",text,1,new MarkdownLocator(List.of("章节"),"n"));
        var bundle=new EvidenceBundle(List.of(item),text.length());
        var candidate=new CandidateAnswer(CandidateAnswer.Decision.ANSWER,"备份保留30天",
                List.of(new GroundedClaim(UUID.randomUUID(),"备份保留30天",List.of(new EvidenceSupport(id,text)))));
        var policy=new BackupBudgetQuestionScopedPolicy(e -> List.of());
        assertThat(policy.verifyAnswer(QaQuestion.normalize("星河项目的备份保留多久？"),candidate,bundle))
                .isEqualTo(QuestionScopedConflictPolicy.AnswerRelevance.RELEVANT);
        assertThat(policy.verifyAnswer(QaQuestion.normalize("海川项目的备份保留多久？"),candidate,bundle))
                .isEqualTo(QuestionScopedConflictPolicy.AnswerRelevance.UNKNOWN);
        var qualified=new QaEvidence(id,SPACE,item.documentId(),item.versionId(),"n","制度","星河项目备份保留30天以上。",1,item.locator());
        assertThat(policy.verifyAnswer(QaQuestion.normalize("星河项目的备份保留多久？"),candidate,
                new EvidenceBundle(List.of(qualified),qualified.excerpt().length())))
                .isEqualTo(QuestionScopedConflictPolicy.AnswerRelevance.UNKNOWN);
        String broken="星河项目备份保留3 0天。";
        var ambiguous=new QaEvidence(id,SPACE,item.documentId(),item.versionId(),"n","制度",broken,1,item.locator());
        var brokenAnswer=new CandidateAnswer(CandidateAnswer.Decision.ANSWER,"备份保留3 0天",
                List.of(new GroundedClaim(UUID.randomUUID(),"备份保留3 0天",List.of(new EvidenceSupport(id,broken)))));
        assertThat(policy.verifyAnswer(QaQuestion.normalize("星河项目的备份保留多久？"),brokenAnswer,
                new EvidenceBundle(List.of(ambiguous),broken.length()))).isEqualTo(QuestionScopedConflictPolicy.AnswerRelevance.UNKNOWN);
        String spacedSubject="星 河项目备份保留30天。";
        var otherSubject=new QaEvidence(id,SPACE,item.documentId(),item.versionId(),"n","制度",spacedSubject,1,item.locator());
        var otherAnswer=new CandidateAnswer(CandidateAnswer.Decision.ANSWER,"备份保留30天",
                List.of(new GroundedClaim(UUID.randomUUID(),"备份保留30天",List.of(new EvidenceSupport(id,spacedSubject)))));
        assertThat(policy.verifyAnswer(QaQuestion.normalize("星河项目的备份保留多久？"),otherAnswer,
                new EvidenceBundle(List.of(otherSubject),spacedSubject.length()))).isEqualTo(QuestionScopedConflictPolicy.AnswerRelevance.UNKNOWN);
        String superscript="星河项目备份保留³⁰天。";
        var unusual=new QaEvidence(id,SPACE,item.documentId(),item.versionId(),"n","制度",superscript,1,item.locator());
        var unusualAnswer=new CandidateAnswer(CandidateAnswer.Decision.ANSWER,"备份保留³⁰天",
                List.of(new GroundedClaim(UUID.randomUUID(),"备份保留³⁰天",List.of(new EvidenceSupport(id,superscript)))));
        assertThat(policy.verifyAnswer(QaQuestion.normalize("星河项目的备份保留多久？"),unusualAnswer,
                new EvidenceBundle(List.of(unusual),superscript.length()))).isEqualTo(QuestionScopedConflictPolicy.AnswerRelevance.UNKNOWN);
    }

    @Test void refusesWhenOtherCandidatesConflictWithTheRequestedFrequencyOrBudgetTitleLinksTheTopics() {
        var a=table("100"); var b=table("120");
        var pair=new DeterministicEvidenceConflictDetector.ConflictPair(a.evidenceId(),b.evidenceId(),EvidenceConflictDetector.ConflictKind.STRUCTURED_TABLE);
        var policy=new BackupBudgetQuestionScopedPolicy(provenTables());
        var monthly=new Evidence(SPACE,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"monthly","制度",
                "星河项目备份保留30天。运维负责人张三每月组织一次恢复演练。",1,new MarkdownLocator(List.of("章节"),"monthly"));
        var yearly=new Evidence(SPACE,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"yearly","制度",
                "星河项目备份保留30天。运维负责人张三每年组织一次恢复演练。",1,new MarkdownLocator(List.of("章节"),"yearly"));
        assertThat(policy.classify(QaQuestion.normalize("星河项目多久组织一次恢复演练？"),pair,List.of(a,b,monthly,yearly)))
                .isEqualTo(QuestionScopedConflictPolicy.Scope.UNKNOWN);
        var linked=new Evidence(SPACE,a.evidenceId(),a.documentId(),a.versionId(),a.nodeId(),"研发预算决定备份保留期限",a.excerpt(),1,a.locator());
        assertThat(policy.classify(QaQuestion.normalize("星河项目的备份保留多久？"),pair,List.of(linked,b,monthly)))
                .isEqualTo(QuestionScopedConflictPolicy.Scope.UNKNOWN);
        var hidden=new Evidence(SPACE,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"hidden","星河项目备份制度",
                "保留期限为60天。",1,new MarkdownLocator(List.of("章节"),"hidden"));
        assertThat(policy.classify(QaQuestion.normalize("星河项目的备份保留多久？"),pair,List.of(a,b,monthly,hidden)))
                .isEqualTo(QuestionScopedConflictPolicy.Scope.UNKNOWN);
        var hiddenTable=new Evidence(SPACE,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"hidden-table","制度",
                "星河项目 | 60",1,new TableLocator("备份保留期限",1,2,1,2));
        assertThat(policy.classify(QaQuestion.normalize("星河项目的备份保留多久？"),pair,List.of(a,b,monthly,hiddenTable)))
                .isEqualTo(QuestionScopedConflictPolicy.Scope.UNKNOWN);
        var contextual=new Evidence(SPACE,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"context","制度",
                "星河项目备份保留30天。",1,new MarkdownLocator(List.of("备份期限由预算决定"),"context"));
        assertThat(policy.classify(QaQuestion.normalize("星河项目的备份保留多久？"),pair,List.of(a,b,contextual)))
                .isEqualTo(QuestionScopedConflictPolicy.Scope.UNKNOWN);
    }
}
