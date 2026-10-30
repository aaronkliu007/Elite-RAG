package com.yunjihai.rag.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class ProposalRecoverySchedulerTest {
    @Test void continuesAfterFailureAndDoesNotOverlapScans() throws Exception {
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var scheduler = new ProposalRecoveryScheduler(() -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("private database detail");
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
            return 3;
        });
        assertThat(scheduler.scanOnce()).isZero();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(scheduler::scanOnce);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(scheduler.scanOnce()).isZero();
            } finally { release.countDown(); }
            assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo(3);
        }
        assertThat(calls).hasValue(2);
    }
}
