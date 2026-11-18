package com.yunjihai.rag.qa.conflict;

import com.yunjihai.rag.qa.domain.CandidateAnswer;
import com.yunjihai.rag.qa.domain.EvidenceBundle;
import com.yunjihai.rag.qa.domain.QaQuestion;
import com.yunjihai.rag.retrieval.domain.Evidence;
import java.util.List;

/** Trusted local deployment extension. It receives authorized but unsanitized candidates: never send them externally. */
public interface QuestionScopedConflictPolicy {
    enum Scope { UNRELATED, RELEVANT, UNKNOWN }
    enum AnswerRelevance { RELEVANT, IRRELEVANT, UNKNOWN }

    Scope classify(QaQuestion question, DeterministicEvidenceConflictDetector.ConflictPair pair,
                   List<Evidence> authorizedScreeningCandidates);
    AnswerRelevance verifyAnswer(QaQuestion question, CandidateAnswer answer, EvidenceBundle evidence);
    default boolean enabled() { return true; }
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
