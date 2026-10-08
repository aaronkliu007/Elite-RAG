package com.yunjihai.rag.runtime;

import com.yunjihai.rag.ingestion.IngestionWorkerPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerSchedulersTest {

    @Test
    void ingestionLeaseMustOutliveConfiguredParserDeadline() {
        assertThatThrownBy(() -> new IngestionWorkerPolicy(Duration.ofMinutes(2), 300))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("入库租约必须长于解析超时")
                .hasNoCause();
        assertThatThrownBy(() -> new IngestionWorkerPolicy(Duration.ofSeconds(301), 300))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("入库租约必须长于解析超时")
                .hasNoCause();

        assertThat(new IngestionWorkerPolicy(Duration.ofMinutes(10), 300).leaseDuration())
                .isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void ingestionSchedulerReturnsImmediatelyWhenNoTaskExists() {
        var scheduler = new IngestionWorkerScheduler(() -> false, "ingestion-test");

        assertThat(scheduler.drainAvailable()).isZero();
    }

    @Test
    void ingestionSchedulerPreventsSameInstanceReentry() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var scheduler = new IngestionWorkerScheduler(() -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            return true;
        }, "ingestion-test");

        var first = CompletableFuture.supplyAsync(scheduler::drainAvailable);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduler.drainAvailable()).isZero();
        assertThat(calls).hasValue(1);
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1);
    }

    @Test
    void ingestionSchedulerContinuesAfterOneCycleFails() {
        var calls = new AtomicInteger();
        var scheduler = new IngestionWorkerScheduler(() -> {
            if (calls.getAndIncrement() == 0) throw new IllegalStateException("temporary");
            return true;
        }, "ingestion-test");

        assertThat(scheduler.drainAvailable()).isZero();
        assertThat(scheduler.drainAvailable()).isEqualTo(1);
        assertThat(calls).hasValue(2);
    }

    @Test
    void projectionSchedulerAlwaysUsesConfiguredWorkerAndTargetIndex() {
        var worker = new AtomicReference<String>();
        var target = new AtomicReference<String>();
        var scheduler = new ProjectionWorkerScheduler((workerId, targetIndex) -> {
            worker.set(workerId);
            target.set(targetIndex);
            return 3;
        }, "projection-test", "rag-chunks-write");

        assertThat(scheduler.drainAvailable()).isEqualTo(3);
        assertThat(worker).hasValue("projection-test");
        assertThat(target).hasValue("rag-chunks-write");
    }
}
