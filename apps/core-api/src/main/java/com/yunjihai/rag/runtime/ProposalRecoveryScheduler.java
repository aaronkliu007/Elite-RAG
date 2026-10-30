package com.yunjihai.rag.runtime;

import org.springframework.scheduling.annotation.Scheduled;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

/** Expires abandoned candidates without retrying any external model call. */
public final class ProposalRecoveryScheduler {
    private static final System.Logger LOGGER = System.getLogger(ProposalRecoveryScheduler.class.getName());
    private final IntSupplier scan;
    private final AtomicBoolean scanning = new AtomicBoolean();

    ProposalRecoveryScheduler(IntSupplier scan) { this.scan = Objects.requireNonNull(scan); }

    @Scheduled(fixedDelayString = "${rag.solution.proposals.recovery.poll-delay:PT30S}",
            initialDelayString = "${rag.solution.proposals.recovery.poll-delay:PT30S}")
    void scheduledScan() { scanOnce(); }

    public int scanOnce() {
        if (!scanning.compareAndSet(false, true)) return 0;
        try { return scan.getAsInt(); }
        catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "候选生成超时扫描失败，等待下一周期");
            return 0;
        } finally { scanning.set(false); }
    }
}
