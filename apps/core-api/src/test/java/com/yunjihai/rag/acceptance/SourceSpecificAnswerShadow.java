package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.qa.domain.CandidateAnswer;
import com.yunjihai.rag.qa.domain.EvidenceBundle;
import com.yunjihai.rag.qa.ExactQuoteCitationSupportVerifier;
import com.yunjihai.rag.retrieval.domain.TableLocator;
import java.text.Normalizer;
import java.util.regex.Pattern;
import com.yunjihai.rag.retrieval.domain.Evidence;

/** Extremely narrow offline experiment; never used for production decisions. */
final class SourceSpecificAnswerShadow {
    private SourceSpecificAnswerShadow() { }
    static boolean supports(String question,CandidateAnswer answer,EvidenceBundle evidence,Evidence expected) {
        if(expected==null || !supports(question,answer,evidence))return false;
        var id=answer.claims().getFirst().supports().getFirst().evidenceId();
        var source=evidence.evidence().stream().filter(item->item.evidenceId().equals(id)).findFirst().orElseThrow();
        return source.spaceId().equals(expected.spaceId()) && source.documentId().equals(expected.documentId())
                && source.versionId().equals(expected.versionId()) && source.nodeId().equals(expected.nodeId())
                && source.title().equals(expected.title()) && source.excerpt().equals(expected.excerpt());
    }
    private static boolean supports(String question,CandidateAnswer answer,EvidenceBundle evidence) {
        if(answer==null || evidence==null || answer.claims().size()!=1
                || answer.claims().getFirst().supports().size()!=1
                || !new ExactQuoteCitationSupportVerifier().isSupported(answer,evidence)) return false;
        var claim=answer.claims().getFirst();
        var support=claim.supports().getFirst();
        var sources=evidence.evidence().stream().filter(item->item.evidenceId().equals(support.evidenceId())).toList();
        if(sources.size()!=1) return false;
        var source=sources.getFirst();
        if(!(source.locator() instanceof TableLocator)) return false;
        var project=SourceSpecificConflictDiagnostic.matchingProject(question,source.title());
        if(project.isEmpty()) return false;
        String text=normalize(source.excerpt());
        String pattern="^项目预算类别金额\\(万元\\)审批日期及版本"+Pattern.quote(project.orElseThrow())
                +"项目研发预算[0-9]+(?:\\.[0-9]+)?未注明$";
        return text.matches(pattern) && text.equals(normalize(support.quote()))
                && text.equals(normalize(claim.text())) && text.equals(normalize(answer.answer()));
    }
    private static String normalize(String text) {
        return Normalizer.normalize(text,Normalizer.Form.NFKC).replaceAll("\\s+", "");
    }
}
