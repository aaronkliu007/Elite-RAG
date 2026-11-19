package com.yunjihai.rag.qa.conflict;

import com.yunjihai.rag.qa.domain.CandidateAnswer;
import com.yunjihai.rag.qa.domain.EvidenceBundle;
import com.yunjihai.rag.qa.domain.QaQuestion;
import com.yunjihai.rag.retrieval.domain.Evidence;
import java.util.List;
import java.util.Optional;

/** Trusted local deployment extension. It receives authorized but unsanitized candidates: never send them externally. */
public interface QuestionScopedConflictPolicy {
    enum Scope { UNRELATED, RELEVANT, UNKNOWN }
    enum AnswerRelevance { RELEVANT, IRRELEVANT, UNKNOWN }

    Scope classify(QaQuestion question, DeterministicEvidenceConflictDetector.ConflictPair pair,
                   List<Evidence> authorizedScreeningCandidates);
    default Scope classifyAll(QaQuestion question,List<DeterministicEvidenceConflictDetector.ConflictPair> pairs,
                              List<Evidence> authorizedScreeningCandidates) {
        if(pairs==null || pairs.isEmpty())return Scope.UNKNOWN;
        for(var pair:pairs)if(classify(question,pair,authorizedScreeningCandidates)!=Scope.UNRELATED)return Scope.UNKNOWN;
        return Scope.UNRELATED;
    }
    /** Supplemental nodes stay local and must belong to an already authorized document. */
    default Optional<List<Evidence>> expandScreening(QaQuestion question,List<Evidence> candidates) {
        return Optional.of(List.copyOf(candidates));
    }
    AnswerRelevance verifyAnswer(QaQuestion question, CandidateAnswer answer, EvidenceBundle evidence);
    default boolean enabled() { return true; }
    /** Reviewed questions must be screened even if retrieved nodes show no conflict. */
    default boolean requiresCompleteScreening(QaQuestion question) { return false; }
    /** Stable, non-sensitive identifier; change it whenever the policy or its rules change. */
    String version();

    static QuestionScopedConflictPolicy strict() {
        return new QuestionScopedConflictPolicy() {
            @Override public boolean enabled() { return false; }
            @Override public String version() { return "strict"; }
            @Override public Scope classify(QaQuestion question, DeterministicEvidenceConflictDetector.ConflictPair pair,
                                            List<Evidence> candidates) { return Scope.UNKNOWN; }
            @Override public AnswerRelevance verifyAnswer(QaQuestion question, CandidateAnswer answer, EvidenceBundle evidence) {
                return AnswerRelevance.UNKNOWN;
            }
        };
    }
}
