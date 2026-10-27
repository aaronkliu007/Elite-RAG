package com.yunjihai.rag.retrieval;

import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.access.PrincipalType;
import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.domain.DegradationReason;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.EvidencePack;
import com.yunjihai.rag.retrieval.domain.RetrievalContext;
import com.yunjihai.rag.retrieval.domain.RetrievalFilter;
import com.yunjihai.rag.retrieval.domain.RetrievalPolicy;
import com.yunjihai.rag.retrieval.domain.RetrievalQuery;
import com.yunjihai.rag.retrieval.domain.RetrievalStats;
import com.yunjihai.rag.retrieval.domain.ScoredCandidate;
import com.yunjihai.rag.retrieval.search.HybridRecallService;
import com.yunjihai.rag.retrieval.selection.EvidenceSelectionService;
import com.yunjihai.rag.retrieval.selection.RerankingService;
import com.yunjihai.rag.retrieval.selection.RetrievalAuthorizationPort;
import com.yunjihai.rag.qa.sanitization.RegexSensitiveContentSanitizer;
import com.yunjihai.rag.qa.sanitization.SensitiveContentSanitizer;
import com.yunjihai.rag.qa.evaluation.EvaluationCase;
import com.yunjihai.rag.qa.evaluation.EvaluationTraceRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class TrustedRetrievalService implements RetrievalService {

    private final PrincipalResolverPort principalResolver;
    private final HybridRecallService recallService;
    private final RerankingService rerankingService;
    private final RetrievalAuthorizationPort authorization;
    private final EvidenceSelectionService selectionService;
    private final EvidenceIdFactory evidenceIdFactory;
    private final RetrievalPolicy policy;
    private final Clock clock;
    private final AuditSink auditSink;
    private final SensitiveContentSanitizer outboundSanitizer;
    private final EvaluationTraceRegistry evaluationTraces;

    public TrustedRetrievalService(
            PrincipalResolverPort principalResolver,
            HybridRecallService recallService,
            RerankingService rerankingService,
            RetrievalAuthorizationPort authorization,
            EvidenceSelectionService selectionService,
            EvidenceIdFactory evidenceIdFactory,
            RetrievalPolicy policy,
            Clock clock,
            AuditSink auditSink) {
        this(principalResolver, recallService, rerankingService, authorization,
                selectionService, evidenceIdFactory, policy, clock, auditSink,
                new RegexSensitiveContentSanitizer(Set.of()), new EvaluationTraceRegistry());
    }

    public TrustedRetrievalService(
            PrincipalResolverPort principalResolver,
            HybridRecallService recallService,
            RerankingService rerankingService,
            RetrievalAuthorizationPort authorization,
            EvidenceSelectionService selectionService,
            EvidenceIdFactory evidenceIdFactory,
            RetrievalPolicy policy,
            Clock clock,
            AuditSink auditSink,
            SensitiveContentSanitizer outboundSanitizer) {
        this(principalResolver, recallService, rerankingService, authorization,
                selectionService, evidenceIdFactory, policy, clock, auditSink,
                outboundSanitizer, new EvaluationTraceRegistry());
    }

    public TrustedRetrievalService(
            PrincipalResolverPort principalResolver,
            HybridRecallService recallService,
            RerankingService rerankingService,
            RetrievalAuthorizationPort authorization,
            EvidenceSelectionService selectionService,
            EvidenceIdFactory evidenceIdFactory,
            RetrievalPolicy policy,
            Clock clock,
            AuditSink auditSink,
            SensitiveContentSanitizer outboundSanitizer,
            EvaluationTraceRegistry evaluationTraces) {
        this.principalResolver = Objects.requireNonNull(principalResolver);
        this.recallService = Objects.requireNonNull(recallService);
        this.rerankingService = Objects.requireNonNull(rerankingService);
        this.authorization = Objects.requireNonNull(authorization);
        this.selectionService = Objects.requireNonNull(selectionService);
        this.evidenceIdFactory = Objects.requireNonNull(evidenceIdFactory);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
        this.auditSink = Objects.requireNonNull(auditSink);
        this.outboundSanitizer = Objects.requireNonNull(outboundSanitizer);
        this.evaluationTraces = Objects.requireNonNull(evaluationTraces);
    }

    @Override
    public EvidencePack retrieve(RetrievalQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        Instant startedAt = clock.instant();
        var progress = new Progress();
        final EvidencePack result;
        try {
            result = retrieveTrusted(query, startedAt, progress);
        } catch (RuntimeException failure) {
            try {
                recordAudit(
                        query,
                        AuditOutcome.FAILED,
                        progress.stats(),
                        progress.reasons,
                        startedAt,
                        clock.instant());
            } catch (RuntimeException auditFailure) {
                failure.addSuppressed(auditFailure);
            }
            throw failure;
        }
        recordAudit(
                query,
                AuditOutcome.SUCCEEDED,
                result.stats(),
                result.degradationReasons(),
                startedAt,
                result.completedAt());
        return result;
    }

    private EvidencePack retrieveTrusted(
            RetrievalQuery query,
            Instant startedAt,
            Progress progress) {
        if (query.requestedEvidenceCount() < policy.minEvidenceCount()
                || query.requestedEvidenceCount() > policy.maxEvidenceCount()) {
            throw new IllegalArgumentException("requested evidence count is outside policy");
        }
        var context = new RetrievalContext(
                startedAt,
                resolvePrincipals(query, startedAt));
        var filter = new RetrievalFilter(
                query.spaceIds(),
                context.effectivePrincipals(),
                true,
                context.startedAt(),
                query.metadataFilter());
        var outboundQuery = outboundSanitizer.sanitizeText(query.queryText());
        if (outboundQuery.blocked()) {
            throw new RetrievalOutboundPolicyException();
        }
        var recalled = recallService.recall(outboundQuery.value(), filter);
        progress.lexicalCount = recalled.lexicalCount();
        progress.vectorCount = recalled.vectorCount();
        progress.fusedCount = recalled.candidates().size();
        progress.reasons.addAll(recalled.degradationReasons());
        Instant checkedAt = clock.instant();
        var requestedVersionsBySpace = documentVersionsBySpace(query, recalled.candidates());
        var allowedVersions = authorize(query, requestedVersionsBySpace, checkedAt);
        var authorizedCandidates = recalled.candidates().stream()
                .filter(candidate -> allowedVersions.contains(new DocumentVersionRef(
                        candidate.candidate().documentId(), candidate.candidate().versionId())))
                .toList();
        progress.reauthorizedCount = authorizedCandidates.size();
        var safeCandidates = sanitizeForReranking(authorizedCandidates);
        evaluationTraces.recordRequest(query.requestId(), safeCandidates.stream()
                .map(value -> new EvaluationCase.EvidenceRef(
                        value.candidate().documentId(), value.candidate().nodeId()))
                .toList());
        var reranked = rerankingService.rerank(outboundQuery.value(), safeCandidates);
        progress.reasons.addAll(reranked.degradationReasons());
        var restored = restoreOriginalCandidates(reranked.candidates(), authorizedCandidates);
        Instant recheckedAt = clock.instant();
        var recheckedVersions = authorize(
                query, documentVersionsBySpace(query, restored), recheckedAt);
        var reauthorized = restored.stream()
                .filter(candidate -> recheckedVersions.contains(new DocumentVersionRef(
                        candidate.candidate().documentId(), candidate.candidate().versionId())))
                .toList();
        progress.reauthorizedCount = reauthorized.size();
        var selected = selectionService.select(
                reauthorized, query.requestedEvidenceCount());
        progress.selectedCount = selected.size();
        Instant completedAt = clock.instant();
        var evidence = toEvidence(selected, completedAt);
        var stats = progress.stats();
        return new EvidencePack(
                query.requestId(),
                evidence,
                !progress.reasons.isEmpty(),
                progress.reasons,
                stats,
                completedAt);
    }

    private Set<PrincipalRef> resolvePrincipals(RetrievalQuery query, Instant startedAt) {
        final Set<PrincipalRef> resolved;
        try {
            var raw = principalResolver.resolve(query.userId(), startedAt);
            resolved = Set.copyOf(Objects.requireNonNull(raw));
        } catch (RuntimeException exception) {
            throw new RetrievalAuthorizationUnavailableException(
                    "current principals are unavailable");
        }
        var userPrincipal = new PrincipalRef(PrincipalType.USER, query.userId());
        if (resolved.isEmpty() || !resolved.contains(userPrincipal)) {
            throw new RetrievalAuthorizationUnavailableException(
                    "current principals are unavailable");
        }
        return resolved;
    }

    private Set<DocumentVersionRef> authorize(
            RetrievalQuery query,
            Map<UUID, Set<DocumentVersionRef>> requestedVersionsBySpace,
            Instant checkedAt) {
        var requestedVersions = requestedVersionsBySpace.values().stream()
                .flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        final Set<DocumentVersionRef> allowed;
        try {
            var raw = authorization.authorize(
                    query.userId(), requestedVersionsBySpace, checkedAt);
            allowed = Set.copyOf(Objects.requireNonNull(raw));
        } catch (RuntimeException exception) {
            throw new RetrievalAuthorizationUnavailableException(
                    "current authorization is unavailable");
        }
        if (!requestedVersions.containsAll(allowed)) {
            throw new RetrievalAuthorizationUnavailableException(
                    "current authorization is unavailable");
        }
        return allowed;
    }

    private static Map<UUID, Set<DocumentVersionRef>> documentVersionsBySpace(
            RetrievalQuery query,
            List<ScoredCandidate> candidates) {
        var mutable = new LinkedHashMap<UUID, LinkedHashSet<DocumentVersionRef>>();
        for (var scored : candidates) {
            var candidate = scored.candidate();
            if (!query.spaceIds().contains(candidate.spaceId())) {
                throw new RetrievalAuthorizationUnavailableException(
                        "current authorization is unavailable");
            }
            mutable.computeIfAbsent(candidate.spaceId(), ignored -> new LinkedHashSet<>())
                    .add(new DocumentVersionRef(candidate.documentId(), candidate.versionId()));
        }
        var immutable = new LinkedHashMap<UUID, Set<DocumentVersionRef>>();
        mutable.forEach((spaceId, versions) -> immutable.put(spaceId, Set.copyOf(versions)));
        return Map.copyOf(immutable);
    }

    private List<Evidence> toEvidence(List<ScoredCandidate> candidates, Instant completedAt) {
        var evidence = new ArrayList<Evidence>(candidates.size());
        for (var scored : candidates) {
            var candidate = scored.candidate();
            evidence.add(new Evidence(
                    candidate.spaceId(),
                    evidenceIdFactory.next(completedAt),
                    candidate.documentId(),
                    candidate.versionId(),
                    candidate.nodeId(),
                    candidate.title(),
                    truncate(candidate.text(), 1200),
                    scored.score(),
                    candidate.locator()));
        }
        return List.copyOf(evidence);
    }

    private List<ScoredCandidate> sanitizeForReranking(List<ScoredCandidate> candidates) {
        var sanitized = new ArrayList<ScoredCandidate>(candidates.size());
        for (var scored : candidates) {
            var candidate = scored.candidate();
            var title = outboundSanitizer.sanitizeText(candidate.title());
            var text = outboundSanitizer.sanitizeText(candidate.text());
            if (title.blocked() || text.blocked()) {
                throw new RetrievalOutboundPolicyException();
            }
            var safeCandidate = new com.yunjihai.rag.retrieval.domain.Candidate(
                    candidate.spaceId(), candidate.candidateId(), candidate.documentId(),
                    candidate.versionId(), candidate.nodeId(), title.value(), text.value(),
                    candidate.locator(), candidate.source(), candidate.sourceRank(),
                    candidate.sourceScore());
            sanitized.add(new ScoredCandidate(
                    safeCandidate, scored.score(), scored.bestSourceRank(), scored.sources()));
        }
        return List.copyOf(sanitized);
    }

    private static List<ScoredCandidate> restoreOriginalCandidates(
            List<ScoredCandidate> reranked,
            List<ScoredCandidate> originals) {
        var byId = new LinkedHashMap<UUID, com.yunjihai.rag.retrieval.domain.Candidate>();
        originals.forEach(value -> byId.put(value.candidate().candidateId(), value.candidate()));
        return reranked.stream().map(value -> new ScoredCandidate(
                Objects.requireNonNull(byId.get(value.candidate().candidateId())),
                value.score(), value.bestSourceRank(), value.sources())).toList();
    }

    private static String truncate(String value, int maxCodePoints) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private void recordAudit(
            RetrievalQuery query,
            AuditOutcome outcome,
            RetrievalStats stats,
            Set<DegradationReason> reasons,
            Instant startedAt,
            Instant finishedAt) {
        long durationMs = Math.max(0, Duration.between(startedAt, finishedAt).toMillis());
        var degradationReasons = reasons.stream()
                .map(Enum::name)
                .sorted()
                .toList();
        var metadata = Map.ofEntries(
                Map.entry("queryCodePoints", Integer.toString(
                        query.queryText().codePointCount(0, query.queryText().length()))),
                Map.entry("lexicalCount", Integer.toString(stats.lexicalCount())),
                Map.entry("vectorCount", Integer.toString(stats.vectorCount())),
                Map.entry("fusedCount", Integer.toString(stats.fusedCount())),
                Map.entry("reauthorizedCount", Integer.toString(stats.reauthorizedCount())),
                Map.entry("selectedCount", Integer.toString(stats.selectedCount())),
                Map.entry("degradationReasons", String.join(",", degradationReasons)),
                Map.entry("durationMs", Long.toString(durationMs)));
        auditSink.record(new AuditCommand(
                query.userId(),
                AuditAction.RETRIEVE,
                "RETRIEVAL",
                query.requestId(),
                outcome,
                metadata));
    }

    private static final class Progress {
        private int lexicalCount;
        private int vectorCount;
        private int fusedCount;
        private int reauthorizedCount;
        private int selectedCount;
        private final EnumSet<DegradationReason> reasons =
                EnumSet.noneOf(DegradationReason.class);

        private RetrievalStats stats() {
            return new RetrievalStats(
                    lexicalCount,
                    vectorCount,
                    fusedCount,
                    reauthorizedCount,
                    selectedCount);
        }
    }
}
