package com.yunjihai.rag.retrieval.search;

import com.yunjihai.rag.retrieval.domain.Candidate;
import com.yunjihai.rag.retrieval.domain.DegradationReason;
import com.yunjihai.rag.retrieval.domain.RecallSource;
import com.yunjihai.rag.retrieval.domain.RetrievalFilter;
import com.yunjihai.rag.retrieval.domain.RetrievalPolicy;
import com.yunjihai.rag.retrieval.domain.ScoredCandidate;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class HybridRecallService {

    private final LexicalRetriever lexicalRetriever;
    private final VectorRetriever vectorRetriever;
    private final ExecutorService executor;
    private final RetrievalPolicy policy;

    public HybridRecallService(
            LexicalRetriever lexicalRetriever,
            VectorRetriever vectorRetriever,
            ExecutorService executor,
            RetrievalPolicy policy) {
        this.lexicalRetriever = Objects.requireNonNull(lexicalRetriever);
        this.vectorRetriever = Objects.requireNonNull(vectorRetriever);
        this.executor = Objects.requireNonNull(executor);
        this.policy = Objects.requireNonNull(policy);
    }

    public HybridRecallResult recall(String queryText, RetrievalFilter filter) {
        Objects.requireNonNull(queryText, "queryText must not be null");
        Objects.requireNonNull(filter, "filter must not be null");
        long deadline = System.nanoTime() + policy.recallTimeout().toNanos();
        Future<List<Candidate>> lexicalFuture = submit(() ->
                lexicalRetriever.retrieve(queryText, filter, policy.lexicalLimit()));
        Future<List<Candidate>> vectorFuture = submit(() ->
                vectorRetriever.retrieve(queryText, filter, policy.vectorLimit()));

        var lexical = await(
                lexicalFuture, deadline, RecallSource.LEXICAL, policy.lexicalLimit());
        var vector = await(
                vectorFuture, deadline, RecallSource.VECTOR, policy.vectorLimit());
        if (!lexical.available() && !vector.available()) {
            throw new RetrievalUnavailableException("all recall branches unavailable");
        }
        var degradationReasons = EnumSet.noneOf(DegradationReason.class);
        if (!lexical.available()) {
            degradationReasons.add(DegradationReason.LEXICAL_UNAVAILABLE);
        }
        if (!vector.available()) {
            degradationReasons.add(DegradationReason.VECTOR_UNAVAILABLE);
        }
        return new HybridRecallResult(
                fuse(lexical.candidates(), vector.candidates()),
                degradationReasons,
                lexical.candidates().size(),
                vector.candidates().size());
    }

    private BranchResult await(
            Future<List<Candidate>> future,
            long deadline,
            RecallSource expectedSource,
            int limit) {
        if (future == null) {
            return BranchResult.unavailable();
        }
        try {
            long remaining = Math.max(0, deadline - System.nanoTime());
            var candidates = future.get(remaining, TimeUnit.NANOSECONDS);
            return new BranchResult(validate(candidates, expectedSource, limit), true);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return BranchResult.unavailable();
        } catch (ExecutionException | TimeoutException | InvalidRecallResultException exception) {
            future.cancel(true);
            return BranchResult.unavailable();
        }
    }

    private Future<List<Candidate>> submit(Callable<List<Candidate>> task) {
        try {
            return executor.submit(task);
        } catch (RejectedExecutionException exception) {
            return null;
        }
    }

    private List<Candidate> validate(
            List<Candidate> candidates,
            RecallSource expectedSource,
            int limit) {
        if (candidates == null || candidates.size() > limit) {
            throw new InvalidRecallResultException("recall result size is invalid");
        }
        var ids = new HashSet<java.util.UUID>();
        for (int index = 0; index < candidates.size(); index++) {
            var candidate = candidates.get(index);
            if (candidate == null
                    || candidate.source() != expectedSource
                    || candidate.sourceRank() != index + 1
                    || !ids.add(candidate.candidateId())
                    || (candidate.sourceScore() != null
                    && !Double.isFinite(candidate.sourceScore()))) {
                throw new InvalidRecallResultException("recall result structure is invalid");
            }
        }
        return List.copyOf(candidates);
    }

    private List<ScoredCandidate> fuse(List<Candidate> lexical, List<Candidate> vector) {
        Map<java.util.UUID, Accumulator> accumulated = new LinkedHashMap<>();
        accumulate(accumulated, lexical);
        accumulate(accumulated, vector);
        return accumulated.values().stream()
                .map(Accumulator::toScoredCandidate)
                .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed()
                        .thenComparingInt(ScoredCandidate::bestSourceRank)
                        .thenComparing(scored -> scored.candidate().candidateId()))
                .limit(policy.fusedLimit())
                .toList();
    }

    private void accumulate(Map<java.util.UUID, Accumulator> accumulated, List<Candidate> candidates) {
        for (var candidate : candidates) {
            accumulated.computeIfAbsent(candidate.candidateId(), ignored -> new Accumulator(candidate))
                    .add(candidate);
        }
    }

    private final class Accumulator {
        private final Candidate candidate;
        private final EnumSet<RecallSource> sources = EnumSet.noneOf(RecallSource.class);
        private double score;
        private int bestSourceRank = Integer.MAX_VALUE;

        private Accumulator(Candidate candidate) {
            this.candidate = candidate;
        }

        private void add(Candidate rankedCandidate) {
            if (!sameIdentity(candidate, rankedCandidate)) {
                throw new RetrievalUnavailableException("candidate identity conflict");
            }
            score += 1.0 / (policy.rrfK() + rankedCandidate.sourceRank());
            bestSourceRank = Math.min(bestSourceRank, rankedCandidate.sourceRank());
            sources.add(rankedCandidate.source());
        }

        private ScoredCandidate toScoredCandidate() {
            return new ScoredCandidate(candidate, score, bestSourceRank, sources);
        }

        private boolean sameIdentity(Candidate first, Candidate second) {
            return first.candidateId().equals(second.candidateId())
                    && first.documentId().equals(second.documentId())
                    && first.versionId().equals(second.versionId())
                    && first.nodeId().equals(second.nodeId())
                    && first.title().equals(second.title())
                    && first.text().equals(second.text())
                    && first.locator().equals(second.locator());
        }
    }

    private record BranchResult(List<Candidate> candidates, boolean available) {

        private static BranchResult unavailable() {
            return new BranchResult(List.of(), false);
        }
    }
}
