package com.yunjihai.rag.api.idempotency;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-08T08:00:00Z");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final String HASH = "a".repeat(64);
    private final InMemoryStore store = new InMemoryStore();
    private final IdempotencyService service = new IdempotencyService(
            store, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(24));

    @Test
    void replaysCompletedResultWithoutExecutingActionAgain() {
        var scope = scope("replay");
        var calls = new AtomicInteger();

        Result first = service.execute(scope, HASH, Result.class,
                () -> new Result("result-" + calls.incrementAndGet()));
        Result replay = service.execute(scope, HASH, Result.class,
                () -> new Result("result-" + calls.incrementAndGet()));

        assertThat(first).isEqualTo(new Result("result-1"));
        assertThat(replay).isEqualTo(first);
        assertThat(calls).hasValue(1);
        assertThat(store.entries.get(scope).expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void rejectsSameKeyWithDifferentHash() {
        var scope = scope("mismatch");
        service.execute(scope, HASH, Result.class, () -> new Result("first"));

        assertThatThrownBy(() -> service.execute(scope, "b".repeat(64), Result.class,
                () -> new Result("second")))
                .isInstanceOf(IdempotencyConflictException.class)
                .extracting("reason")
                .isEqualTo(IdempotencyConflictException.Reason.REQUEST_MISMATCH);
    }

    @Test
    void rejectsDuplicateWhileFirstExecutionIsInProgressAndUsesOneExecutor() throws Exception {
        var scope = scope("concurrent");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.execute(scope, HASH, Result.class, () -> {
                calls.incrementAndGet();
                entered.countDown();
                await(release);
                return new Result("done");
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            var duplicate = executor.submit((Callable<Throwable>) () -> {
                try {
                    service.execute(scope, HASH, Result.class, () -> {
                        calls.incrementAndGet();
                        return new Result("wrong");
                    });
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });

            assertThat(duplicate.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(IdempotencyConflictException.class)
                    .extracting("reason")
                    .isEqualTo(IdempotencyConflictException.Reason.IN_PROGRESS);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(new Result("done"));
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void failedActionIsNotCachedAndCanBeRetried() {
        var scope = scope("failure");

        assertThatThrownBy(() -> service.execute(scope, HASH, Result.class,
                () -> { throw new IllegalStateException("failed"); }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(store.entries).doesNotContainKey(scope);
        assertThat(service.execute(scope, HASH, Result.class, () -> new Result("retry")))
                .isEqualTo(new Result("retry"));
    }

    @Test
    void validatesPrintableKeyAndSha256RequestHash() {
        assertThatThrownBy(() -> new IdempotencyScope(ACTOR, "upload", "bad\nkey"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.execute(scope("hash"), "not-sha-256", Result.class,
                () -> new Result("never")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(IdempotencyService.sha256("中文输入".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .matches("[0-9a-f]{64}");
    }

    private static IdempotencyScope scope(String key) {
        return new IdempotencyScope(ACTOR, "document.upload", key);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    record Result(String value) { }

    static final class InMemoryStore implements IdempotencyStore {
        private final Map<IdempotencyScope, Entry> entries = new HashMap<>();

        @Override
        public synchronized Claim claim(
                IdempotencyScope scope, String requestHash, Instant now, Instant expiresAt) {
            Entry current = entries.get(scope);
            if (current != null && !current.expiresAt().isAfter(now)) {
                entries.remove(scope);
                current = null;
            }
            if (current == null) {
                entries.put(scope, new Entry(requestHash, null, expiresAt));
                return new Claim.Acquired();
            }
            if (!current.requestHash().equals(requestHash)) return new Claim.HashMismatch();
            if (current.result() == null) return new Claim.InProgress();
            return new Claim.Completed(current.result());
        }

        @Override
        public synchronized void complete(
                IdempotencyScope scope, String requestHash, StoredHttpResult result, Instant at) {
            Entry current = entries.get(scope);
            entries.put(scope, new Entry(current.requestHash(), result, current.expiresAt()));
        }

        @Override
        public synchronized void release(IdempotencyScope scope, String requestHash) {
            Entry current = entries.get(scope);
            if (current != null && current.requestHash().equals(requestHash) && current.result() == null) {
                entries.remove(scope);
            }
        }

        record Entry(String requestHash, StoredHttpResult result, Instant expiresAt) { }
    }
}
