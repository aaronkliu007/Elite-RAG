package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.qa.domain.*;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class QuestionScopedAnswerShadowTest {
    @Test
    void derivesTheBackupDurationFromTheQuestionBoundSourceRatherThanAFixedAnswer() {
        assertAccepted("星河项目的备份保留多久？","备份保留30天",
                "星河项目每天凌晨两点执行一次全量备份，备份保留30天。运维负责人周宁每月组织一次恢复演练。");
        assertAccepted("海川项目的备份保留多久？","备份保留60日",
                "海川项目备份保留60日。");
    }

    @Test
    void derivesTheRestoreFrequencyFromTheQuestionBoundSource() {
        assertAccepted("星河项目多久组织一次恢复演练？","每月组织一次恢复演练",
                "星河项目每天凌晨两点执行一次全量备份，备份保留30天。运维负责人周宁每月组织一次恢复演练。");
    }

    @Test
    void acceptsAWholeRelevantSourceSentenceAndItsExactPartialQuote() {
        String source="星河项目每天凌晨两点执行一次全量备份，备份保留30天。运维负责人周宁每月组织一次恢复演练。";
        assertAcceptedQuote("星河项目的备份保留多久？", "星河项目每天凌晨两点执行一次全量备份，备份保留30天。",source);
        assertAcceptedQuote("星河项目多久组织一次恢复演练？", "运维负责人周宁每月组织一次恢复演练。",source);
    }

    private static void assertAcceptedQuote(String question,String sentence,String excerpt) {
        var data=fixture(sentence,excerpt,sentence);
        assertThat(QuestionScopedAnswerShadow.supports(question,data.answer(),data.bundle())).isTrue();
    }

    @Test
    void refusesDifferentSubjectNegationExtraConditionsAndCompoundQuestions() {
        assertRejected("星河项目的备份保留多久？","备份保留30天","新星河项目备份保留30天。");
        assertRejected("星河项目的备份保留多久？","备份保留30天","星河项目未备份保留30天。");
        assertRejected("星河项目的备份保留多久？","备份保留30天","星河项目备份保留30天以上。");
        assertRejected("星河项目的预算和备份保留多久？","备份保留30天","星河项目备份保留30天。");
        assertRejected("星河项目多久组织一次恢复演练？","每月组织一次恢复演练",
                "星河项目备份保留30天。运维负责人周宁未每月组织一次恢复演练。");
    }

    @Test
    void refusesUnrelatedAnswersEvenIfTheQuoteReallyExists() {
        assertRejected("星河项目的备份保留多久？","研发预算","星河项目研发预算为100万元。");
    }

    private static void assertAccepted(String question,String claim,String excerpt) {
        var data=fixture(claim,excerpt);
        assertThat(QuestionScopedAnswerShadow.supports(question,data.answer(),data.bundle())).isTrue();
    }
    private static void assertRejected(String question,String claim,String excerpt) {
        var data=fixture(claim,excerpt);
        assertThat(QuestionScopedAnswerShadow.supports(question,data.answer(),data.bundle())).isFalse();
    }
    private record Sample(CandidateAnswer answer,EvidenceBundle bundle) { }
    private static Sample fixture(String claim,String excerpt) {
        return fixture(claim,excerpt,excerpt);
    }
    private static Sample fixture(String claim,String excerpt,String quote) {
        var id=UUID.randomUUID();
        var source=new QaEvidence(id,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"node",
                "虚构材料",excerpt,1.0,new MarkdownLocator(List.of("章节"),"node"));
        var support=new EvidenceSupport(id,quote);
        var answer=new CandidateAnswer(CandidateAnswer.Decision.ANSWER,claim,
                List.of(new GroundedClaim(UUID.randomUUID(),claim,List.of(support))));
        return new Sample(answer,new EvidenceBundle(List.of(source),excerpt.codePointCount(0,excerpt.length())));
    }
}
