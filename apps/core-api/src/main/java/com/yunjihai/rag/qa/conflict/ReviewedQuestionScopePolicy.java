package com.yunjihai.rag.qa.conflict;

import com.yunjihai.rag.qa.ExactQuoteCitationSupportVerifier;
import com.yunjihai.rag.qa.QuestionDigestPort;
import com.yunjihai.rag.qa.domain.*;
import com.yunjihai.rag.retrieval.domain.Evidence;

import java.util.*;
import java.util.function.Function;

/** Explicit, question-specific human review. Configuration is trusted and defaults to absent. */
public final class ReviewedQuestionScopePolicy implements QuestionScopedConflictPolicy {
    public enum Relation { RELEVANT, UNRELATED }
    public record DocumentReview(UUID documentId,String contextFingerprint,Relation relation) { }
    public record ClaimReview(UUID documentId,UUID versionId,String nodeId,String claimDigest,String quoteDigest) { }
    public record QuestionReview(String questionDigest,UUID approvalId,boolean allDocumentsReviewed,
                                 List<DocumentReview> documents,List<ClaimReview> claims) { }
    public record Manifest(List<QuestionReview> questions) { }
    public record SignedManifest(String payload,String signature) { }
    private record Prepared(Map<UUID,DocumentReview> documents,Set<ClaimReview> claims) { }

    private final String version;
    private final Map<String,Prepared> reviews;
    private final Function<Evidence,Optional<String>> currentFingerprint;
    private final QuestionDigestPort digest;
    private final java.util.function.BiPredicate<Evidence,List<Evidence>> completeRelevantDocument;
    private final Function<Evidence,Optional<List<Evidence>>> loadFullDocument;

    public ReviewedQuestionScopePolicy(String version,List<QuestionReview> questions,
            Function<Evidence,Optional<String>> currentFingerprint,QuestionDigestPort digest) {
        this(version,questions,currentFingerprint,digest,(ignored,candidates)->false);
    }
    public ReviewedQuestionScopePolicy(String version,List<QuestionReview> questions,
            Function<Evidence,Optional<String>> currentFingerprint,QuestionDigestPort digest,
            java.util.function.BiPredicate<Evidence,List<Evidence>> completeRelevantDocument) {
        this(version,questions,currentFingerprint,digest,completeRelevantDocument,ignored->Optional.empty());
    }
    public ReviewedQuestionScopePolicy(String version,List<QuestionReview> questions,
            Function<Evidence,Optional<String>> currentFingerprint,QuestionDigestPort digest,
            java.util.function.BiPredicate<Evidence,List<Evidence>> completeRelevantDocument,
            Function<Evidence,Optional<List<Evidence>>> loadFullDocument) {
        this.loadFullDocument=Objects.requireNonNull(loadFullDocument);
        if(version==null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,47}"))throw new IllegalArgumentException("INVALID_REVIEW_VERSION");
        this.version=version; this.currentFingerprint=Objects.requireNonNull(currentFingerprint); this.digest=Objects.requireNonNull(digest);
        this.completeRelevantDocument=Objects.requireNonNull(completeRelevantDocument);
        if(questions==null || questions.isEmpty() || questions.size()>64)throw new IllegalArgumentException("INVALID_REVIEW_MANIFEST");
        var compiled=new HashMap<String,Prepared>();
        for(var question:questions) {
            if(question==null || !validDigest(question.questionDigest()) || question.approvalId()==null || !question.allDocumentsReviewed()
                    || question.documents()==null || question.documents().isEmpty()
                    || question.documents().size()>128 || question.claims()==null || question.claims().isEmpty() || question.claims().size()>128)
                throw new IllegalArgumentException("INVALID_REVIEW_MANIFEST");
            var documents=new HashMap<UUID,DocumentReview>();
            for(var item:question.documents()) {
                if(item==null || item.documentId()==null || item.relation()==null || !validDigest(item.contextFingerprint())
                        || documents.putIfAbsent(item.documentId(),item)!=null)throw new IllegalArgumentException("INVALID_REVIEW_MANIFEST");
            }
            var claims=new HashSet<ClaimReview>();
            for(var claim:question.claims()) {
                if(claim==null || claim.documentId()==null || claim.versionId()==null || claim.nodeId()==null || claim.nodeId().isBlank()
                        || claim.nodeId().codePointCount(0,claim.nodeId().length())>200
                        || !validDigest(claim.claimDigest()) || !validDigest(claim.quoteDigest()) || !claims.add(claim)
                        || !documents.containsKey(claim.documentId()) || documents.get(claim.documentId()).relation()!=Relation.RELEVANT)
                    throw new IllegalArgumentException("INVALID_REVIEW_MANIFEST");
            }
            if(compiled.putIfAbsent(question.questionDigest(),new Prepared(Map.copyOf(documents),Set.copyOf(claims)))!=null)
                throw new IllegalArgumentException("INVALID_REVIEW_MANIFEST");
        }
        reviews=Map.copyOf(compiled);
    }

    @Override public String version() { return version; }
    @Override public boolean requiresCompleteScreening(QaQuestion question) {
        return question!=null && reviews.containsKey(digest.digest("question:"+question.value()));
    }
    @Override public Optional<List<Evidence>> expandScreening(QaQuestion question,List<Evidence> candidates) {
        if(question==null || candidates==null || candidates.isEmpty())return Optional.empty();
        try {
            var review=reviews.get(digest.digest("question:"+question.value()));
            if(review==null)return Optional.empty();
            var result=new ArrayList<>(candidates);
            var visited=new HashSet<UUID>();
            for(var item:candidates) {
                var approved=review.documents().get(item.documentId());
                if(approved==null || currentFingerprint.apply(item).filter(approved.contextFingerprint()::equals).isEmpty())return Optional.empty();
                if(approved.relation()!=Relation.RELEVANT || !visited.add(item.documentId()))continue;
                if(completeRelevantDocument.test(item,result))continue;
                var full=loadFullDocument.apply(item);
                if(full.isEmpty() || full.get().isEmpty())return Optional.empty();
                for(var extra:full.get()) {
                    if(extra==null || !extra.spaceId().equals(item.spaceId()) || !extra.documentId().equals(item.documentId())
                            || !extra.versionId().equals(item.versionId()) || !extra.title().equals(item.title()))return Optional.empty();
                    var existing=result.stream().filter(e->e.documentId().equals(extra.documentId()) && e.nodeId().equals(extra.nodeId())).toList();
                    if(existing.isEmpty())result.add(extra);
                    else if(existing.stream().anyMatch(e->!e.excerpt().equals(extra.excerpt()) || !e.locator().equals(extra.locator())))return Optional.empty();
                    if(result.size()>128)return Optional.empty();
                }
            }
            return Optional.of(List.copyOf(result));
        } catch(RuntimeException unavailable) { return Optional.empty(); }
    }
    @Override public Scope classify(QaQuestion question,DeterministicEvidenceConflictDetector.ConflictPair pair,List<Evidence> candidates) {
        return pair==null ? Scope.UNKNOWN : classifyAll(question,List.of(pair),candidates);
    }
    @Override public Scope classifyAll(QaQuestion question,List<DeterministicEvidenceConflictDetector.ConflictPair> pairs,List<Evidence> candidates) {
        if(question==null || pairs==null || candidates==null || candidates.isEmpty() || candidates.size()>128)return Scope.UNKNOWN;
        try {
            var review=reviews.get(digest.digest("question:"+question.value())); if(review==null)return Scope.UNKNOWN;
            var byId=new HashMap<UUID,Evidence>();
            var verifiedDocuments=new HashSet<UUID>();
            for(var candidate:candidates) {
                if(candidate==null || byId.putIfAbsent(candidate.evidenceId(),candidate)!=null)return Scope.UNKNOWN;
                var approved=review.documents().get(candidate.documentId());
                if(approved==null || !currentFingerprint.apply(candidate).filter(approved.contextFingerprint()::equals).isPresent())return Scope.UNKNOWN;
                if(approved.relation()==Relation.RELEVANT && verifiedDocuments.add(candidate.documentId())
                        && !completeRelevantDocument.test(candidate,candidates))return Scope.UNKNOWN;
            }
            for(var pair:pairs) {
                if(pair==null || (pair.kind()!=EvidenceConflictDetector.ConflictKind.TYPED_FACT
                        && pair.kind()!=EvidenceConflictDetector.ConflictKind.STRUCTURED_TABLE))return Scope.UNKNOWN;
                var a=byId.get(pair.firstEvidenceId()); var b=byId.get(pair.secondEvidenceId());
                if(a==null || b==null || a.evidenceId().equals(b.evidenceId()) || a.documentId().equals(b.documentId())
                        || review.documents().get(a.documentId()).relation()!=Relation.UNRELATED
                        || review.documents().get(b.documentId()).relation()!=Relation.UNRELATED)return Scope.UNKNOWN;
            }
            return Scope.UNRELATED;
        } catch(RuntimeException unavailable) { return Scope.UNKNOWN; }
    }

    @Override public AnswerRelevance verifyAnswer(QaQuestion question,CandidateAnswer answer,EvidenceBundle evidence) {
        if(question==null || answer==null || evidence==null || !new ExactQuoteCitationSupportVerifier().isSupported(answer,evidence))return AnswerRelevance.UNKNOWN;
        try {
            var review=reviews.get(digest.digest("question:"+question.value())); if(review==null)return AnswerRelevance.UNKNOWN;
            var items=new HashMap<UUID,QaEvidence>();
            for(var item:evidence.evidence())if(items.putIfAbsent(item.evidenceId(),item)!=null)return AnswerRelevance.UNKNOWN;
            for(var claim:answer.claims()) {
                String claimDigest=digest.digest("claim:"+claim.text());
                for(var support:claim.supports()) {
                    var item=items.get(support.evidenceId()); if(item==null)return AnswerRelevance.UNKNOWN;
                    var document=review.documents().get(item.documentId());
                    if(document==null || document.relation()!=Relation.RELEVANT
                            || !review.claims().contains(new ClaimReview(item.documentId(),item.versionId(),item.nodeId(),
                            claimDigest,digest.digest("quote:"+support.quote()))))return AnswerRelevance.UNKNOWN;
                }
            }
            return AnswerRelevance.RELEVANT;
        } catch(RuntimeException unavailable) { return AnswerRelevance.UNKNOWN; }
    }
    private static boolean validDigest(String value) { return value!=null && value.matches("[0-9a-f]{64}"); }
}
