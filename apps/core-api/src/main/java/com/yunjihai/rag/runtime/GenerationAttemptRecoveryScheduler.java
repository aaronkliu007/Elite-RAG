package com.yunjihai.rag.runtime;

import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

/** Periodically expires abandoned generation attempts without re-executing external work. */
public final class GenerationAttemptRecoveryScheduler {
    private static final System.Logger LOGGER =
            System.getLogger(GenerationAttemptRecoveryScheduler.class.getName());

    private final IntSupplier scan;
    private final AtomicBoolean scanning = new AtomicBoolean();

    GenerationAttemptRecoveryScheduler(IntSupplier scan) {
        this.scan = Objects.requireNonNull(scan);
    }

    @Scheduled(
            fixedDelayString = "${rag.solution.generation.recovery.poll-delay:PT30S}",
            initialDelayString = "${rag.solution.generation.recovery.poll-delay:PT30S}")
    void scheduledScan() {
        scanOnce();
    }

    public int scanOnce() {
        if (!scanning.compareAndSet(false, true)) return 0;
        try {
            return scan.getAsInt();
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "分章生成超时尝试扫描失败", failure);
            return 0;
        } finally {
            scanning.set(false);
        }
    }
}
