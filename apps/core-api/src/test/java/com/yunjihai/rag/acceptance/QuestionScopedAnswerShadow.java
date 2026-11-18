package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.qa.domain.CandidateAnswer;
import com.yunjihai.rag.qa.domain.EvidenceBundle;
import com.yunjihai.rag.qa.ExactQuoteCitationSupportVerifier;
import java.text.Normalizer;
import java.util.regex.Pattern;

/** Offline-only experiment; no production bean. */
final class QuestionScopedAnswerShadow {
    private QuestionScopedAnswerShadow() { }
    private static final Pattern QUESTION = Pattern.compile("^([\\p{IsHan}A-Za-z0-9]{2,30}项目)(的备份保留多久|多久组织一次恢复演练)\\??$");

    static boolean supports(String question,CandidateAnswer answer,EvidenceBundle evidence) {
        if (question == null || answer == null || evidence == null ||
                answer.decision() != CandidateAnswer.Decision.ANSWER || answer.claims().size() != 1 ||
                !new ExactQuoteCitationSupportVerifier().isSupported(answer,evidence)) return false;
        var questionMatch=QUESTION.matcher(normalize(question));
        if (!questionMatch.matches()) return false;
        var subject=Pattern.quote(questionMatch.group(1));
        var sourcePattern=Pattern.compile("^"+subject+
                "(?:每天凌晨[\\p{IsHan}0-9]{1,4}点执行一次全量备份,)?"+
                "(?<backup>备份保留\\d{1,4}(?:天|日))。"+
                "(?:运维负责人[\\p{IsHan}]{2,5}(?<drill>每(?:月|周|年)组织一次恢复演练)。)?$");
        var claim=answer.claims().get(0);
        if (claim.supports().isEmpty()) return false;
        for (var support:claim.supports()) {
            var sources=evidence.evidence().stream().filter(item->item.evidenceId().equals(support.evidenceId())).toList();
            if (sources.size()!=1) return false;
            String fullSource=normalize(sources.get(0).excerpt());
            var source=sourcePattern.matcher(fullSource);
            if (!source.matches()) return false;
            boolean backup=questionMatch.group(2).startsWith("的");
            var extracted=source.group(backup ? "backup" : "drill");
            var sentences=fullSource.split("。");
            if (extracted==null || sentences.length<(backup?1:2)) return false;
            String candidate=normalize(answer.answer()).replaceFirst("。$", "");
            String claimText=normalize(claim.text()).replaceFirst("。$", "");
            if (!candidate.equals(claimText) || !(candidate.equals(extracted)
                    || candidate.equals(sentences[backup?0:1]))) return false;
        }
        return true;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("\\s+", "");
    }
}
