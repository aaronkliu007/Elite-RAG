package com.yunjihai.rag.retrieval.selection;

import com.yunjihai.rag.retrieval.domain.DegradationReason;
import com.yunjihai.rag.retrieval.domain.RetrievalPolicy;
import com.yunjihai.rag.retrieval.domain.ScoredCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class RerankingService {

    private final RerankerPort reranker;
    private final ExecutorService executor;
    private final RetrievalPolicy policy;

    public RerankingService(
            RerankerPort reranker,
            ExecutorService executor,
            RetrievalPolicy policy) {
        this.reranker = Objects.requireNonNull(reranker);
        this.executor = Objects.requireNonNull(executor);
        this.policy = Objects.requireNonNull(policy);
    }

    public RerankResult rerank(String queryText, List<ScoredCandidate> candidates) {
        Objects.requireNonNull(queryText, "queryText must not be null");
        Objects.requireNonNull(candidates, "candidates must not be null");
        var limited = List.copyOf(candidates.stream().limit(policy.fusedLimit()).toList());
        if (limited.isEmpty()) {
            return new RerankResult(List.of(), Set.of());
        }
        final Future<List<RerankScore>> future;
        try {
            future = executor.submit(() -> reranker.rerank(queryText, limited));
        } catch (RejectedExecutionException exception) {
            return fallback(limited);
        }
        final List<RerankScore> scores;
        try {
            scores = future.get(policy.rerankTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return fallback(limited);
        } catch (ExecutionException | TimeoutException exception) {
            future.cancel(true);
            return fallback(limited);
        }

        Map<java.util.UUID, ScoredCandidate> byId = new HashMap<>();
        Map<java.util.UUID, Integer> originalIndex = new HashMap<>();
        for (int index = 0; index < limited.size(); index++) {
            var candidate = limited.get(index);
            byId.put(candidate.candidate().candidateId(), candidate);
            originalIndex.put(candidate.candidate().candidateId(), index);
        }
        if (!valid(scores, byId)) {
            return fallback(limited);
        }
        var reranked = new ArrayList<ScoredCandidate>();
        for (var score : scores) {
            var original = byId.get(score.candidateId());
            reranked.add(new ScoredCandidate(
                    original.candidate(),
                    score.score(),
                    original.bestSourceRank(),
                    original.sources()));
        }
        reranked.sort(Comparator.comparingDouble(ScoredCandidate::score).reversed()
                .thenComparingInt(candidate -> originalIndex.get(candidate.candidate().candidateId()))
                .thenComparing(candidate -> candidate.candidate().candidateId()));
        return new RerankResult(reranked, Set.of());
    }

    private static boolean valid(
            List<RerankScore> scores,
            Map<java.util.UUID, ScoredCandidate> candidates) {
        if (scores == null || scores.size() != candidates.size()) {
            return false;
        }
        var seen = new HashSet<java.util.UUID>();
        for (var score : scores) {
            if (score == null
                    || !Double.isFinite(score.score())
                    || !candidates.containsKey(score.candidateId())
                    || !seen.add(score.candidateId())) {
                return false;
            }
        }
        return true;
    }

    private static RerankResult fallback(List<ScoredCandidate> candidates) {
        return new RerankResult(
                candidates,
                Set.of(DegradationReason.RERANKER_UNAVAILABLE));
    }
}
