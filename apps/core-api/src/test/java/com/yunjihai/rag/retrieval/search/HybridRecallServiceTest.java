package com.yunjihai.rag.retrieval.search;

import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.access.PrincipalType;
import com.yunjihai.rag.retrieval.domain.Candidate;
import com.yunjihai.rag.retrieval.domain.DegradationReason;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import com.yunjihai.rag.retrieval.domain.RecallSource;
import com.yunjihai.rag.retrieval.domain.RetrievalFilter;
import com.yunjihai.rag.retrieval.domain.RetrievalPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class HybridRecallServiceTest {

    private static final UUID USER_ID = uuid(1);
    private static final UUID SPACE_ID = uuid(2);
    private static final UUID OVERLAP_ID = uuid(10);
    private static final UUID LEXICAL_ID = uuid(11);
    private static final UUID VECTOR_ID = uuid(12);

    @Test
    void fusesBothBranchesWithDeterministicRrf() {
        var filter = filter();
        var lexicalFilter = new AtomicReference<RetrievalFilter>();
        var vectorFilter = new AtomicReference<RetrievalFilter>();
        var lexicalLimit = new AtomicInteger();
        var vectorLimit = new AtomicInteger();
        LexicalRetriever lexical = (queryText, receivedFilter, limit) -> {
            lexicalFilter.set(receivedFilter);
            lexicalLimit.set(limit);
            return List.of(
                    candidate(LEXICAL_ID, 21, RecallSource.LEXICAL, 1, "全文第一"),
                    candidate(OVERLAP_ID, 20, RecallSource.LEXICAL, 2, "共同候选"));
        };
        VectorRetriever vector = (queryText, receivedFilter, limit) -> {
            vectorFilter.set(receivedFilter);
            vectorLimit.set(limit);
            return List.of(
                    candidate(VECTOR_ID, 22, RecallSource.VECTOR, 1, "向量第一"),
                    candidate(OVERLAP_ID, 20, RecallSource.VECTOR, 2, "共同候选"));
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var service = new HybridRecallService(
                    lexical, vector, executor, RetrievalPolicy.defaults());

            var result = service.recall("知识检索", filter);

            assertThat(result.candidates())
                    .extracting(scored -> scored.candidate().candidateId())
                    .containsExactly(OVERLAP_ID, LEXICAL_ID, VECTOR_ID);
            assertThat(result.candidates().getFirst().score())
                    .isCloseTo(2.0 / 62.0, within(1.0e-12));
            assertThat(result.candidates().get(1).score())
                    .isCloseTo(1.0 / 61.0, within(1.0e-12));
            assertThat(result.candidates().getFirst().bestSourceRank()).isEqualTo(2);
            assertThat(result.candidates().getFirst().sources())
                    .containsExactlyInAnyOrder(RecallSource.LEXICAL, RecallSource.VECTOR);
            assertThat(result.lexicalCount()).isEqualTo(2);
            assertThat(result.vectorCount()).isEqualTo(2);
            assertThat(result.degradationReasons()).isEmpty();
            assertThat(lexicalFilter.get()).isSameAs(filter);
            assertThat(vectorFilter.get()).isSameAs(filter);
            assertThat(lexicalLimit.get()).isEqualTo(50);
            assertThat(vectorLimit.get()).isEqualTo(50);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void degradesToTheHealthyBranchWhenEitherRetrieverThrows() {
        var healthy = candidate(LEXICAL_ID, 21, RecallSource.LEXICAL, 1, "健康正文");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var lexicalFailed = new HybridRecallService(
                    (query, filter, limit) -> {
                        throw new IllegalStateException("供应商秘密错误和查询正文");
                    },
                    (query, filter, limit) -> List.of(candidate(
                            VECTOR_ID, 22, RecallSource.VECTOR, 1, "向量正文")),
                    executor,
                    RetrievalPolicy.defaults());
            var vectorFailed = new HybridRecallService(
                    (query, filter, limit) -> List.of(healthy),
                    (query, filter, limit) -> {
                        throw new IllegalStateException("供应商秘密错误和候选正文");
                    },
                    executor,
                    RetrievalPolicy.defaults());

            var lexicalDegraded = lexicalFailed.recall("不得泄漏的查询", filter());
            var vectorDegraded = vectorFailed.recall("不得泄漏的查询", filter());

            assertThat(lexicalDegraded.candidates())
                    .extracting(scored -> scored.candidate().candidateId())
                    .containsExactly(VECTOR_ID);
            assertThat(lexicalDegraded.degradationReasons())
                    .containsExactly(DegradationReason.LEXICAL_UNAVAILABLE);
            assertThat(lexicalDegraded.lexicalCount()).isZero();
            assertThat(vectorDegraded.candidates())
                    .extracting(scored -> scored.candidate().candidateId())
                    .containsExactly(LEXICAL_ID);
            assertThat(vectorDegraded.degradationReasons())
                    .containsExactly(DegradationReason.VECTOR_UNAVAILABLE);
            assertThat(vectorDegraded.vectorCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancelsTimedOutBranchAndReturnsTheHealthyBranch() throws InterruptedException {
        var blockerStarted = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        var blockerInterrupted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            LexicalRetriever blocked = (query, filter, limit) -> {
                blockerStarted.countDown();
                try {
                    releaseBlocker.await();
                } catch (InterruptedException exception) {
                    blockerInterrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return List.of();
            };
            var service = new HybridRecallService(
                    blocked,
                    (query, filter, limit) -> List.of(candidate(
                            VECTOR_ID, 22, RecallSource.VECTOR, 1, "向量正文")),
                    executor,
                    policy(Duration.ofMillis(80)));

            var result = service.recall("超时测试", filter());

            assertThat(blockerStarted.getCount()).isZero();
            assertThat(blockerInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(result.degradationReasons())
                    .containsExactly(DegradationReason.LEXICAL_UNAVAILABLE);
            assertThat(result.candidates())
                    .extracting(scored -> scored.candidate().candidateId())
                    .containsExactly(VECTOR_ID);
        } finally {
            releaseBlocker.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failsClosedWhenBothBranchesThrowOrTimeOutWithoutLeakingText()
            throws InterruptedException {
        var throwingExecutor = Executors.newFixedThreadPool(2);
        try {
            var throwing = new HybridRecallService(
                    (query, filter, limit) -> {
                        throw new IllegalStateException("全文秘密正文");
                    },
                    (query, filter, limit) -> {
                        throw new IllegalStateException("向量秘密正文");
                    },
                    throwingExecutor,
                    RetrievalPolicy.defaults());

            assertThatThrownBy(() -> throwing.recall("不得泄漏的查询", filter()))
                    .isInstanceOf(RetrievalUnavailableException.class)
                    .hasMessageNotContaining("不得泄漏")
                    .hasMessageNotContaining("全文秘密")
                    .hasMessageNotContaining("向量秘密");
        } finally {
            throwingExecutor.shutdownNow();
        }

        var releaseBlockers = new CountDownLatch(1);
        var interrupted = new CountDownLatch(2);
        var timeoutExecutor = Executors.newFixedThreadPool(2);
        try {
            LexicalRetriever blockedLexical = (query, filter, limit) -> {
                awaitInterruptibly(releaseBlockers, interrupted);
                return List.of();
            };
            VectorRetriever blockedVector = (query, filter, limit) -> {
                awaitInterruptibly(releaseBlockers, interrupted);
                return List.of();
            };
            var timedOut = new HybridRecallService(
                    blockedLexical,
                    blockedVector,
                    timeoutExecutor,
                    policy(Duration.ofMillis(80)));

            assertThatThrownBy(() -> timedOut.recall("双路超时", filter()))
                    .isInstanceOf(RetrievalUnavailableException.class);
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseBlockers.countDown();
            timeoutExecutor.shutdownNow();
        }
    }

    @Test
    void degradesAnInvalidBranchAndCapsFusedResults() {
        var duplicateId = uuid(500);
        var invalidLexicalResults = List.of(
                candidates(51, RecallSource.LEXICAL, 1000),
                List.of(
                        candidate(duplicateId, 30, RecallSource.LEXICAL, 1, "重复一"),
                        candidate(duplicateId, 31, RecallSource.LEXICAL, 2, "重复二")),
                List.of(
                        candidate(uuid(501), 32, RecallSource.LEXICAL, 1, "排名一"),
                        candidate(uuid(502), 33, RecallSource.LEXICAL, 1, "排名重复")),
                List.of(
                        candidate(uuid(503), 34, RecallSource.LEXICAL, 1, "排名一"),
                        candidate(uuid(504), 35, RecallSource.LEXICAL, 3, "排名跳跃")),
                List.of(candidate(uuid(505), 36, RecallSource.VECTOR, 1, "来源错配")));
        var executor = Executors.newFixedThreadPool(2);
        try {
            for (var invalid : invalidLexicalResults) {
                var service = new HybridRecallService(
                        (query, filter, limit) -> invalid,
                        (query, filter, limit) -> List.of(candidate(
                                VECTOR_ID, 22, RecallSource.VECTOR, 1, "健康向量")),
                        executor,
                        RetrievalPolicy.defaults());

                var result = service.recall("非法分支", filter());

                assertThat(result.candidates())
                        .extracting(scored -> scored.candidate().candidateId())
                        .containsExactly(VECTOR_ID);
                assertThat(result.degradationReasons())
                        .containsExactly(DegradationReason.LEXICAL_UNAVAILABLE);
            }

            var capped = new HybridRecallService(
                    (query, filter, limit) -> candidates(25, RecallSource.LEXICAL, 2000),
                    (query, filter, limit) -> candidates(25, RecallSource.VECTOR, 3000),
                    executor,
                    RetrievalPolicy.defaults()).recall("融合上限", filter());
            assertThat(capped.candidates()).hasSize(30);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failsClosedForCrossBranchIdentityConflictOrTwoInvalidBranches() {
        var conflictId = uuid(600);
        var lexical = candidate(conflictId, 40, RecallSource.LEXICAL, 1, "全文正文");
        var conflicting = List.of(
                new Candidate(
                        lexical.spaceId(), conflictId, uuid(999), lexical.versionId(), lexical.nodeId(), lexical.title(),
                        lexical.text(), lexical.locator(), RecallSource.VECTOR, 1, 0.9),
                new Candidate(
                        lexical.spaceId(), conflictId, lexical.documentId(), uuid(999), lexical.nodeId(), lexical.title(),
                        lexical.text(), lexical.locator(), RecallSource.VECTOR, 1, 0.9),
                new Candidate(
                        lexical.spaceId(), conflictId, lexical.documentId(), lexical.versionId(), "other-node", lexical.title(),
                        lexical.text(), lexical.locator(), RecallSource.VECTOR, 1, 0.9),
                new Candidate(
                        lexical.spaceId(), conflictId, lexical.documentId(), lexical.versionId(), lexical.nodeId(), "其他标题",
                        lexical.text(), lexical.locator(), RecallSource.VECTOR, 1, 0.9),
                new Candidate(
                        lexical.spaceId(), conflictId, lexical.documentId(), lexical.versionId(), lexical.nodeId(), lexical.title(),
                        "向量正文", lexical.locator(), RecallSource.VECTOR, 1, 0.9),
                new Candidate(
                        lexical.spaceId(), conflictId, lexical.documentId(), lexical.versionId(), lexical.nodeId(), lexical.title(),
                        lexical.text(), new PageLocator(99, null), RecallSource.VECTOR, 1, 0.9));
        var executor = Executors.newFixedThreadPool(2);
        try {
            for (var vectorConflict : conflicting) {
                var service = new HybridRecallService(
                        (query, filter, limit) -> List.of(lexical),
                        (query, filter, limit) -> List.of(vectorConflict),
                        executor,
                        RetrievalPolicy.defaults());

                assertThatThrownBy(() -> service.recall("身份冲突", filter()))
                        .isInstanceOf(RetrievalUnavailableException.class)
                        .hasMessageNotContaining("全文正文")
                        .hasMessageNotContaining("向量正文");
            }

            var twoInvalid = new HybridRecallService(
                    (query, filter, limit) -> List.of(
                            candidate(uuid(700), 50, RecallSource.LEXICAL, 1, "一"),
                            candidate(uuid(700), 51, RecallSource.LEXICAL, 2, "二")),
                    (query, filter, limit) -> candidates(51, RecallSource.VECTOR, 4000),
                    executor,
                    RetrievalPolicy.defaults());
            assertThatThrownBy(() -> twoInvalid.recall("双路非法", filter()))
                    .isInstanceOf(RetrievalUnavailableException.class);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void treatsRejectedSubmissionsAsUnavailableBranchesAndManagesAcceptedWork()
            throws InterruptedException {
        try (var rejectLexical = new RejectingExecutor(Set.of(1))) {
            var result = new HybridRecallService(
                    (query, filter, limit) -> List.of(candidate(
                            LEXICAL_ID, 21, RecallSource.LEXICAL, 1, "全文正文")),
                    (query, filter, limit) -> List.of(candidate(
                            VECTOR_ID, 22, RecallSource.VECTOR, 1, "向量正文")),
                    rejectLexical,
                    RetrievalPolicy.defaults()).recall("拒绝全文", filter());

            assertThat(result.candidates())
                    .extracting(scored -> scored.candidate().candidateId())
                    .containsExactly(VECTOR_ID);
            assertThat(result.degradationReasons())
                    .containsExactly(DegradationReason.LEXICAL_UNAVAILABLE);
        }

        try (var rejectVector = new RejectingExecutor(Set.of(2))) {
            var result = new HybridRecallService(
                    (query, filter, limit) -> List.of(candidate(
                            LEXICAL_ID, 21, RecallSource.LEXICAL, 1, "全文正文")),
                    (query, filter, limit) -> List.of(candidate(
                            VECTOR_ID, 22, RecallSource.VECTOR, 1, "向量正文")),
                    rejectVector,
                    RetrievalPolicy.defaults()).recall("拒绝向量", filter());

            assertThat(result.candidates())
                    .extracting(scored -> scored.candidate().candidateId())
                    .containsExactly(LEXICAL_ID);
            assertThat(result.degradationReasons())
                    .containsExactly(DegradationReason.VECTOR_UNAVAILABLE);
        }

        try (var rejectBoth = new RejectingExecutor(Set.of(1, 2))) {
            var service = new HybridRecallService(
                    (query, filter, limit) -> List.of(),
                    (query, filter, limit) -> List.of(),
                    rejectBoth,
                    RetrievalPolicy.defaults());

            assertThatThrownBy(() -> service.recall("双路拒绝", filter()))
                    .isInstanceOf(RetrievalUnavailableException.class);
        }

        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try (var rejectSecond = new RejectingExecutor(Set.of(2))) {
            var service = new HybridRecallService(
                    (query, filter, limit) -> {
                        started.countDown();
                        awaitInterruptibly(release, interrupted);
                        return List.of();
                    },
                    (query, filter, limit) -> List.of(),
                    rejectSecond,
                    policy(Duration.ofMillis(80)));

            assertThatThrownBy(() -> service.recall("拒绝后取消", filter()))
                    .isInstanceOf(RetrievalUnavailableException.class);
            assertThat(started.getCount()).isZero();
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
    }

    private static RetrievalFilter filter() {
        return new RetrievalFilter(
                Set.of(SPACE_ID),
                Set.of(new PrincipalRef(PrincipalType.USER, USER_ID)),
                true,
                Instant.parse("2026-09-28T03:00:00Z"),
                Map.of("year", "2026"));
    }

    private static RetrievalPolicy policy(Duration recallTimeout) {
        return new RetrievalPolicy(
                50, 50, 30, 60, 3, 0.85,
                recallTimeout, Duration.ofMillis(800), 8, 15);
    }

    private static void awaitInterruptibly(CountDownLatch blocker, CountDownLatch interrupted) {
        try {
            blocker.await();
        } catch (InterruptedException exception) {
            interrupted.countDown();
            Thread.currentThread().interrupt();
        }
    }

    private static Candidate candidate(
            UUID candidateId,
            int identitySeed,
            RecallSource source,
            int rank,
            String text) {
        return new Candidate(
                SPACE_ID,
                candidateId,
                uuid(identitySeed),
                uuid(identitySeed + 100),
                "node-" + identitySeed,
                "标题-" + identitySeed,
                text,
                new PageLocator(identitySeed, null),
                source,
                rank,
                1.0 / rank);
    }

    private static List<Candidate> candidates(int count, RecallSource source, int seed) {
        var candidates = new ArrayList<Candidate>(count);
        for (int index = 0; index < count; index++) {
            candidates.add(candidate(
                    uuid(seed + index),
                    seed + index,
                    source,
                    index + 1,
                    "候选-" + index));
        }
        return List.copyOf(candidates);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }

    private static final class RejectingExecutor extends AbstractExecutorService {
        private final java.util.concurrent.ExecutorService delegate =
                Executors.newFixedThreadPool(2);
        private final Set<Integer> rejectedCalls;
        private final AtomicInteger submissions = new AtomicInteger();

        private RejectingExecutor(Set<Integer> rejectedCalls) {
            this.rejectedCalls = Set.copyOf(rejectedCalls);
        }

        @Override
        public void execute(Runnable command) {
            if (rejectedCalls.contains(submissions.incrementAndGet())) {
                throw new RejectedExecutionException("capacity protected");
            }
            delegate.execute(command);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public void close() {
            shutdownNow();
        }
    }
}
