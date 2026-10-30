package com.yunjihai.rag.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationAttemptRecoverySchedulerTest {

    @Test
    void continuesAfterOneFailedScanWithoutDuplicatingModelWork() {
        var calls = new AtomicInteger();
        var scheduler = new GenerationAttemptRecoveryScheduler(() -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("database unavailable");
            return 2;
        });

        assertThat(scheduler.scanOnce()).isZero();
        assertThat(scheduler.scanOnce()).isEqualTo(2);
        assertThat(calls.get()).isEqualTo(2);
    }
}
