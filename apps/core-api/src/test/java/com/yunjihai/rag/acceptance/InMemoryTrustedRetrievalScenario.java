package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.access.PrincipalType;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.RetrievalService;
import com.yunjihai.rag.retrieval.TrustedRetrievalService;
import com.yunjihai.rag.retrieval.domain.Candidate;
import com.yunjihai.rag.retrieval.domain.EvidencePack;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import com.yunjihai.rag.retrieval.domain.RecallSource;
import com.yunjihai.rag.retrieval.domain.RetrievalPolicy;
import com.yunjihai.rag.retrieval.domain.RetrievalQuery;
import com.yunjihai.rag.retrieval.search.HybridRecallService;
import com.yunjihai.rag.retrieval.selection.EvidenceSelectionService;
import com.yunjihai.rag.retrieval.selection.RerankScore;
import com.yunjihai.rag.retrieval.selection.RerankingService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

final class InMemoryTrustedRetrievalScenario implements AutoCloseable {

    private static final UUID REQUEST_ID = uuid(1);
    private static final UUID USER_ID = uuid(2);
    private static final UUID SPACE_ID = uuid(3);
    private static final Instant NOW = Instant.parse("2026-09-28T07:00:00Z");

    private final UUID revokedDocumentId = uuid(100);
    private final UUID replacedDocumentId = uuid(101);
    private final UUID nearDuplicateDocumentOne = uuid(102);
    private final UUID nearDuplicateDocumentTwo = uuid(103);
    private final UUID cappedDocumentId = uuid(104);
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final List<Candidate> lexicalCandidates;
    private final List<Candidate> vectorCandidates;

    InMemoryTrustedRetrievalScenario() {
        var revoked = candidate(10, revokedDocumentId, RecallSource.LEXICAL, 1, "撤权候选正文");
        var replaced = candidate(11, replacedDocumentId, RecallSource.LEXICAL, 2, "旧发布版本正文");
        lexicalCandidates = List.of(
                revoked,
                replaced,
                candidate(12, nearDuplicateDocumentOne, RecallSource.LEXICAL, 3,
                        "云基华海， AI  知识库方案！"),
                candidate(13, cappedDocumentId, RecallSource.LEXICAL, 4, "甲甲甲甲"),
                candidate(14, cappedDocumentId, RecallSource.LEXICAL, 5, "乙乙乙乙"),
                candidate(15, cappedDocumentId, RecallSource.LEXICAL, 6, "丙丙丙丙"),
                candidate(16, cappedDocumentId, RecallSource.LEXICAL, 7, "丁丁丁丁"),
                candidate(17, uuid(107), RecallSource.LEXICAL, 8, "戊戊戊戊"),
                candidate(18, uuid(108), RecallSource.LEXICAL, 9, "己己己己"));
        vectorCandidates = List.of(
                asVector(revoked, 1),
                asVector(replaced, 2),
                candidate(19, nearDuplicateDocumentTwo, RecallSource.VECTOR, 3,
                        "云基华海 ai 知识库方案"),
                candidate(20, uuid(109), RecallSource.VECTOR, 4, "庚庚庚庚"),
                candidate(21, uuid(110), RecallSource.VECTOR, 5, "辛辛辛辛"));
    }

    EvidencePack retrieveHealthy() {
        return service(false, false).retrieve(query());
    }

    EvidencePack retrieveWithVectorFailure() {
        return service(false, true).retrieve(query());
    }

    EvidencePack retrieveWithBothRecallBranchesFailed() {
        return service(true, true).retrieve(query());
    }

    UUID revokedDocumentId() {
        return revokedDocumentId;
    }

    UUID replacedDocumentId() {
        return replacedDocumentId;
    }

    Set<UUID> nearDuplicateDocumentIds() {
        return Set.of(nearDuplicateDocumentOne, nearDuplicateDocumentTwo);
    }

    UUID cappedDocumentId() {
        return cappedDocumentId;
    }

    private RetrievalService service(boolean lexicalFailed, boolean vectorFailed) {
        var policy = RetrievalPolicy.defaults();
        var idSequence = new AtomicInteger();
        var revokedAfterRecall = new HashSet<UUID>();
        var currentPublishedVersions = new HashMap<UUID, UUID>();
        lexicalCandidates.forEach(candidate -> currentPublishedVersions.put(
                candidate.documentId(), candidate.versionId()));
        vectorCandidates.forEach(candidate -> currentPublishedVersions.put(
                candidate.documentId(), candidate.versionId()));
        var recall = new HybridRecallService(
                (query, filter, limit) -> {
                    if (lexicalFailed) {
                        throw new IllegalStateException("lexical unavailable");
                    }
                    return lexicalCandidates;
                },
                (query, filter, limit) -> {
                    if (vectorFailed) {
                        throw new IllegalStateException("vector unavailable");
                    }
                    return vectorCandidates;
                },
                executor,
                policy);
        var reranking = new RerankingService(
                (query, candidates) -> {
                    revokedAfterRecall.add(revokedDocumentId);
                    currentPublishedVersions.put(replacedDocumentId, uuid(8888));
                    return candidates.stream()
                            .map(candidate -> new RerankScore(
                                    candidate.candidate().candidateId(), candidate.score()))
                            .toList();
                },
                executor,
                policy);
        return new TrustedRetrievalService(
                (userId, at) -> Set.of(new PrincipalRef(PrincipalType.USER, userId)),
                recall,
                reranking,
                (userId, spaceId, requested, checkedAt) -> requested.stream()
                        .filter(reference -> userId.equals(USER_ID))
                        .filter(reference -> spaceId.equals(SPACE_ID))
                        .filter(reference -> !revokedAfterRecall.contains(reference.documentId()))
                        .filter(reference -> reference.versionId().equals(
                                currentPublishedVersions.get(reference.documentId())))
                        .collect(Collectors.toUnmodifiableSet()),
                new EvidenceSelectionService(policy),
                at -> uuid(9000 + idSequence.getAndIncrement()),
                policy,
                Clock.fixed(NOW, ZoneOffset.UTC),
                AuditSink.noop());
    }

    private static RetrievalQuery query() {
        return new RetrievalQuery(
                REQUEST_ID,
                USER_ID,
                SPACE_ID,
                "企业知识检索",
                Map.of("industry", "software"),
                8);
    }

    private static Candidate candidate(
            int candidateSeed,
            UUID documentId,
            RecallSource source,
            int rank,
            String text) {
        return new Candidate(
                uuid(candidateSeed),
                documentId,
                uuid(1000 + candidateSeed),
                "node-" + candidateSeed,
                "标题-" + candidateSeed,
                text,
                new PageLocator(candidateSeed, null),
                source,
                rank,
                1.0 / rank);
    }

    private static Candidate asVector(Candidate candidate, int rank) {
        return new Candidate(
                candidate.candidateId(),
                candidate.documentId(),
                candidate.versionId(),
                candidate.nodeId(),
                candidate.title(),
                candidate.text(),
                candidate.locator(),
                RecallSource.VECTOR,
                rank,
                1.0 / rank);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
