package com.yunjihai.rag.retrieval.selection;

import com.yunjihai.rag.retrieval.domain.Candidate;
import com.yunjihai.rag.retrieval.domain.DegradationReason;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import com.yunjihai.rag.retrieval.domain.RecallSource;
import com.yunjihai.rag.retrieval.domain.RetrievalPolicy;
import com.yunjihai.rag.retrieval.domain.ScoredCandidate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RerankingServiceTest {

    @Test
    void reranksAtMostThirtyCandidatesWithStableTies() {
        var input = scoredCandidates(35);
        var received = new AtomicReference<List<ScoredCandidate>>();
        RerankerPort reranker = (queryText, candidates) -> {
            received.set(candidates);
            var scores = new ArrayList<RerankScore>();
            for (int index = 0; index < candidates.size(); index++) {
                double score = index == 29 ? 20 : index <= 1 ? 10 : 9 - index;
                scores.add(new RerankScore(candidates.get(index).candidate().candidateId(), score));
            }
            return scores;
        };
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = new RerankingService(
                    reranker, executor, RetrievalPolicy.defaults())
                    .rerank("知识检索", input);

            assertThat(received.get()).hasSize(30);
            assertThatThrownBy(() -> received.get().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(result.candidates()).hasSize(30);
            assertThat(result.candidates())
                    .extracting(scored -> scored.candidate().candidateId())
                    .startsWith(uuid(29), uuid(0), uuid(1));
            assertThat(result.candidates().getFirst().score()).isEqualTo(20);
            assertThat(result.degradationReasons()).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fallsBackCompletelyForExceptionAndEveryMalformedResult() {
        var input = scoredCandidates(35);
        var expectedFallback = input.subList(0, 30);
        var validScores = scoresFor(expectedFallback);
        var unknown = new ArrayList<>(validScores);
        unknown.set(0, new RerankScore(uuid(9999), 99));
        var duplicate = new ArrayList<>(validScores);
        duplicate.set(1, new RerankScore(validScores.getFirst().candidateId(), 98));
        var missing = new ArrayList<>(validScores.subList(0, 29));
        var nan = new ArrayList<>(validScores);
        nan.set(0, new RerankScore(validScores.getFirst().candidateId(), Double.NaN));
        var infinite = new ArrayList<>(validScores);
        infinite.set(0, new RerankScore(validScores.getFirst().candidateId(), Double.POSITIVE_INFINITY));
        var invalidResults = new ArrayList<List<RerankScore>>();
        invalidResults.add(unknown);
        invalidResults.add(duplicate);
        invalidResults.add(missing);
        invalidResults.add(nan);
        invalidResults.add(infinite);
        invalidResults.add(null);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var throwing = new RerankingService(
                    (query, candidates) -> {
                        throw new IllegalStateException("供应商错误含查询和正文");
                    },
                    executor,
                    RetrievalPolicy.defaults()).rerank("不得泄漏的查询", input);
            assertFallback(throwing, expectedFallback);

            for (var invalid : invalidResults) {
                var result = new RerankingService(
                        (query, candidates) -> invalid,
                        executor,
                        RetrievalPolicy.defaults()).rerank("非法结果", input);
                assertFallback(result, expectedFallback);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancelsTimedOutRerankerAndFallsBack() throws InterruptedException {
        var input = scoredCandidates(10);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            RerankerPort blocked = (query, candidates) -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return scoresFor(candidates);
            };
            var result = new RerankingService(
                    blocked,
                    executor,
                    policy(Duration.ofMillis(80))).rerank("超时重排", input);

            assertThat(started.getCount()).isZero();
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertFallback(result, input);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void fallsBackWhenTheExecutorRejectsSubmission() {
        var input = scoredCandidates(10);
        var executor = Executors.newSingleThreadExecutor();
        executor.shutdownNow();

        var result = new RerankingService(
                (query, candidates) -> scoresFor(candidates),
                executor,
                RetrievalPolicy.defaults()).rerank("容量保护", input);

        assertFallback(result, input);
    }

    @Test
    void optionalLocalFallbackPrioritizesQuestionTermsWithoutDroppingCandidates() {
        var input = new ArrayList<>(scoredCandidates(2));
        var first = input.get(0); var second = input.get(1);
        input.set(0, withText(first, "北辰平台运维手册", "北辰平台的服务运行说明"));
        input.set(1, withText(second, "北辰权限管理说明", "北辰平台分为管理员、编辑者和浏览者三类角色"));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = new RerankingService((query, candidates) -> { throw new IllegalStateException(); },
                    executor, RetrievalPolicy.defaults(), true).rerank("北辰平台有哪些角色？", input);
            assertThat(result.candidates()).containsExactly(input.get(1), input.get(0));
            assertThat(result.degradationReasons()).containsExactly(DegradationReason.RERANKER_UNAVAILABLE);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void localFallbackPreservesOriginalOrderWhenNoMeaningfulOverlap() {
        var input = scoredCandidates(3); var executor = Executors.newSingleThreadExecutor();
        try {
            var result = new RerankingService((query, candidates) -> { throw new IllegalStateException(); },
                    executor, RetrievalPolicy.defaults(), true).rerank("不存在的词汇", input);
            assertThat(result.candidates()).containsExactlyElementsOf(input);
        } finally { executor.shutdownNow(); }
    }

    private static ScoredCandidate withText(ScoredCandidate original, String title, String text) {
        var c = original.candidate();
        return new ScoredCandidate(new Candidate(c.spaceId(),c.candidateId(),c.documentId(),c.versionId(),
                c.nodeId(),title,text,c.locator(),c.source(),c.sourceRank(),c.sourceScore()),
                original.score(),original.bestSourceRank(),original.sources());
    }

    private static void assertFallback(RerankResult result, List<ScoredCandidate> expected) {
        assertThat(result.candidates()).containsExactlyElementsOf(expected);
        assertThat(result.degradationReasons())
                .containsExactly(DegradationReason.RERANKER_UNAVAILABLE);
    }

    private static List<RerankScore> scoresFor(List<ScoredCandidate> candidates) {
        return candidates.stream()
                .map(candidate -> new RerankScore(
                        candidate.candidate().candidateId(), candidate.score()))
                .toList();
    }

    private static RetrievalPolicy policy(Duration rerankTimeout) {
        return new RetrievalPolicy(
                50, 50, 30, 60, 3, 0.85,
                Duration.ofMillis(1200), rerankTimeout, 8, 15);
    }

    private static List<ScoredCandidate> scoredCandidates(int count) {
        var candidates = new ArrayList<ScoredCandidate>();
        for (int index = 0; index < count; index++) {
            var candidate = new Candidate(
                    uuid(9000),
                    uuid(index),
                    uuid(1000 + index),
                    uuid(2000 + index),
                    "node-" + index,
                    "标题-" + index,
                    "候选正文-" + index,
                    new PageLocator(index + 1, null),
                    RecallSource.LEXICAL,
                    index + 1,
                    1.0 / (index + 1));
            candidates.add(new ScoredCandidate(
                    candidate,
                    1.0 / (60 + index + 1),
                    index + 1,
                    Set.of(RecallSource.LEXICAL)));
        }
        return List.copyOf(candidates);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
