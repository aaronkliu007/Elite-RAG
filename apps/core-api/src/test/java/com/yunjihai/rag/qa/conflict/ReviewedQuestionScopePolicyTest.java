package com.yunjihai.rag.qa.conflict;

import com.yunjihai.rag.qa.domain.*;
import com.yunjihai.rag.retrieval.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewedQuestionScopePolicyTest {
    private static final String HASH="a".repeat(64), CHANGED="b".repeat(64);
    private static final UUID SPACE=UUID.randomUUID();
    private static Evidence item(String text) {
        return new Evidence(SPACE,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID().toString(),
                "制度",text,1,new MarkdownLocator(List.of("章节"),"node"));
    }
    @Test void requiresReviewedQuestionEveryDocumentAndExactApprovedClaim() {
        var a=item("预算100万元"); var b=item("预算120万元"); var source=item("备份保留30天。");
        Function<String,String> digest=ReviewedQuestionScopePolicyTest::sha256;
        var q=QaQuestion.normalize("备份多久？");
        var review=new ReviewedQuestionScopePolicy.QuestionReview(digest.apply("question:"+q.value()),UUID.randomUUID(),true,List.of(
                new ReviewedQuestionScopePolicy.DocumentReview(a.documentId(),HASH,ReviewedQuestionScopePolicy.Relation.UNRELATED),
                new ReviewedQuestionScopePolicy.DocumentReview(b.documentId(),HASH,ReviewedQuestionScopePolicy.Relation.UNRELATED),
                new ReviewedQuestionScopePolicy.DocumentReview(source.documentId(),HASH,ReviewedQuestionScopePolicy.Relation.RELEVANT)),
                List.of(new ReviewedQuestionScopePolicy.ClaimReview(source.documentId(),source.versionId(),source.nodeId(),
                        digest.apply("claim:备份保留30天"),digest.apply("quote:"+source.excerpt()))));
        var policy=new ReviewedQuestionScopePolicy("reviewed-test-v1",List.of(review),e->Optional.of(HASH),digest::apply);
        var pair=new DeterministicEvidenceConflictDetector.ConflictPair(a.evidenceId(),b.evidenceId(),EvidenceConflictDetector.ConflictKind.TYPED_FACT);
        assertThat(policy.classify(q,pair,List.of(a,b,source))).isEqualTo(QuestionScopedConflictPolicy.Scope.UNRELATED);
        var altered=new ReviewedQuestionScopePolicy("reviewed-test-v1",List.of(review),e->Optional.of(CHANGED),digest::apply);
        assertThat(altered.classify(q,pair,List.of(a,b,source))).isEqualTo(QuestionScopedConflictPolicy.Scope.UNKNOWN);
        assertThat(policy.classify(q,pair,List.of(a,b,source,item("额外未知")))).isEqualTo(QuestionScopedConflictPolicy.Scope.UNKNOWN);
        var relevantPair=new DeterministicEvidenceConflictDetector.ConflictPair(a.evidenceId(),source.evidenceId(),EvidenceConflictDetector.ConflictKind.TYPED_FACT);
        assertThat(policy.classify(q,relevantPair,List.of(a,b,source))).isEqualTo(QuestionScopedConflictPolicy.Scope.UNKNOWN);
        var qa=new QaEvidence(source.evidenceId(),SPACE,source.documentId(),source.versionId(),source.nodeId(),source.title(),source.excerpt(),1,source.locator());
        var claim=new GroundedClaim(UUID.randomUUID(),"备份保留30天",List.of(new EvidenceSupport(source.evidenceId(),source.excerpt())));
        var answer=new CandidateAnswer(CandidateAnswer.Decision.ANSWER,claim.text(),List.of(claim));
        assertThat(policy.verifyAnswer(q,answer,new EvidenceBundle(List.of(qa),source.excerpt().length())))
                .isEqualTo(QuestionScopedConflictPolicy.AnswerRelevance.RELEVANT);
        var otherNode=new QaEvidence(source.evidenceId(),SPACE,source.documentId(),source.versionId(),"other-node",source.title(),source.excerpt(),1,source.locator());
        assertThat(policy.verifyAnswer(q,answer,new EvidenceBundle(List.of(otherNode),source.excerpt().length())))
                .isEqualTo(QuestionScopedConflictPolicy.AnswerRelevance.UNKNOWN);
        var differentQuote=new GroundedClaim(UUID.randomUUID(),"备份保留30天",List.of(new EvidenceSupport(source.evidenceId(),"备份保留30天")));
        var unapprovedQuote=new CandidateAnswer(CandidateAnswer.Decision.ANSWER,differentQuote.text(),List.of(differentQuote));
        assertThat(policy.verifyAnswer(q,unapprovedQuote,new EvidenceBundle(List.of(qa),source.excerpt().length())))
                .isEqualTo(QuestionScopedConflictPolicy.AnswerRelevance.UNKNOWN);
        assertThat(policy.verifyAnswer(QaQuestion.normalize("其他问题？"),answer,new EvidenceBundle(List.of(qa),source.excerpt().length())))
                .isEqualTo(QuestionScopedConflictPolicy.AnswerRelevance.UNKNOWN);
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Test void rejectsMissingApprovalAndDuplicateOrInconsistentReviewRecords() {
        var document=UUID.randomUUID(); var version=UUID.randomUUID();
        var relevant=new ReviewedQuestionScopePolicy.DocumentReview(document,HASH,ReviewedQuestionScopePolicy.Relation.RELEVANT);
        var claim=new ReviewedQuestionScopePolicy.ClaimReview(document,version,"node",HASH,HASH);
        var unapproved=new ReviewedQuestionScopePolicy.QuestionReview(HASH,UUID.randomUUID(),false,List.of(relevant),List.of(claim));
        assertThatThrownBy(()->new ReviewedQuestionScopePolicy("review-v1",List.of(unapproved),e->Optional.of(HASH),value->HASH))
                .isInstanceOf(IllegalArgumentException.class);
        var duplicate=new ReviewedQuestionScopePolicy.QuestionReview(HASH,UUID.randomUUID(),true,List.of(relevant,relevant),List.of(claim));
        assertThatThrownBy(()->new ReviewedQuestionScopePolicy("review-v1",List.of(duplicate),e->Optional.of(HASH),value->HASH))
                .isInstanceOf(IllegalArgumentException.class);
        var unrelated=new ReviewedQuestionScopePolicy.DocumentReview(document,HASH,ReviewedQuestionScopePolicy.Relation.UNRELATED);
        var wrongRole=new ReviewedQuestionScopePolicy.QuestionReview(HASH,UUID.randomUUID(),true,List.of(unrelated),List.of(claim));
        assertThatThrownBy(()->new ReviewedQuestionScopePolicy("review-v1",List.of(wrongRole),e->Optional.of(HASH),value->HASH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void validatesEachCandidateOnlyOnceForMultipleConflictPairs() {
        var a=item("预算100万元"); var b=item("预算120万元"); var source=item("备份保留30天。");
        var q=QaQuestion.normalize("备份多久？");
        var docs=List.of(new ReviewedQuestionScopePolicy.DocumentReview(a.documentId(),HASH,ReviewedQuestionScopePolicy.Relation.UNRELATED),
                new ReviewedQuestionScopePolicy.DocumentReview(b.documentId(),HASH,ReviewedQuestionScopePolicy.Relation.UNRELATED),
                new ReviewedQuestionScopePolicy.DocumentReview(source.documentId(),HASH,ReviewedQuestionScopePolicy.Relation.RELEVANT));
        var claim=new ReviewedQuestionScopePolicy.ClaimReview(source.documentId(),source.versionId(),source.nodeId(),HASH,HASH);
        var review=new ReviewedQuestionScopePolicy.QuestionReview(sha256("question:"+q.value()),UUID.randomUUID(),true,docs,List.of(claim));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var policy=new ReviewedQuestionScopePolicy("review-v1",List.of(review),e->{calls.incrementAndGet();return Optional.of(HASH);},ReviewedQuestionScopePolicyTest::sha256);
        var pair=new DeterministicEvidenceConflictDetector.ConflictPair(a.evidenceId(),b.evidenceId(),EvidenceConflictDetector.ConflictKind.TYPED_FACT);
        assertThat(policy.classifyAll(q,List.of(pair,pair),List.of(a,b,source))).isEqualTo(QuestionScopedConflictPolicy.Scope.UNRELATED);
        assertThat(calls).hasValue(3);
    }

    @Test void acceptsOnlySeparatelyApprovedQuestionDigestsForTheSameEvidence() {
        var a=item("预算100万元"); var b=item("预算120万元"); var source=item("备份保留30天。");
        var docs=List.of(new ReviewedQuestionScopePolicy.DocumentReview(a.documentId(),HASH,ReviewedQuestionScopePolicy.Relation.UNRELATED),
                new ReviewedQuestionScopePolicy.DocumentReview(b.documentId(),HASH,ReviewedQuestionScopePolicy.Relation.UNRELATED),
                new ReviewedQuestionScopePolicy.DocumentReview(source.documentId(),HASH,ReviewedQuestionScopePolicy.Relation.RELEVANT));
        var claim=new ReviewedQuestionScopePolicy.ClaimReview(source.documentId(),source.versionId(),source.nodeId(),
                sha256("claim:备份保留30天"),sha256("quote:"+source.excerpt()));
        var first=QaQuestion.normalize("备份保留多久？"); var alias=QaQuestion.normalize("备份保留多长时间？");
        var reviews=List.of(new ReviewedQuestionScopePolicy.QuestionReview(sha256("question:"+first.value()),UUID.randomUUID(),true,docs,List.of(claim)),
                new ReviewedQuestionScopePolicy.QuestionReview(sha256("question:"+alias.value()),UUID.randomUUID(),true,docs,List.of(claim)));
        var policy=new ReviewedQuestionScopePolicy("review-v1",reviews,e->Optional.of(HASH),ReviewedQuestionScopePolicyTest::sha256);
        var pair=new DeterministicEvidenceConflictDetector.ConflictPair(a.evidenceId(),b.evidenceId(),EvidenceConflictDetector.ConflictKind.TYPED_FACT);
        for(var question:List.of(first,alias))assertThat(policy.classifyAll(question,List.of(pair),List.of(a,b,source)))
                .isEqualTo(QuestionScopedConflictPolicy.Scope.UNRELATED);
        assertThat(policy.classifyAll(QaQuestion.normalize("备份一定保留多久？"),List.of(pair),List.of(a,b,source)))
                .isEqualTo(QuestionScopedConflictPolicy.Scope.UNKNOWN);
    }
}
