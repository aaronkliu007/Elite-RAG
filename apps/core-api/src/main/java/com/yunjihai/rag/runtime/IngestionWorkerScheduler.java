package com.yunjihai.rag.runtime;

import com.yunjihai.rag.ingestion.IngestionWorker;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 单实例互斥的解析任务轮询器；一次周期最多处理一个租约。 */
public final class IngestionWorkerScheduler {

    private static final System.Logger LOGGER =
            System.getLogger(IngestionWorkerScheduler.class.getName());

    private final BooleanSupplier runOnce;
    private final String workerId;
    private final AtomicBoolean draining = new AtomicBoolean();

    public IngestionWorkerScheduler(IngestionWorker worker, String workerId) {
        this(() -> worker.runOnce(workerId), workerId);
    }

    IngestionWorkerScheduler(BooleanSupplier runOnce, String workerId) {
        this.runOnce = Objects.requireNonNull(runOnce);
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("解析 Worker 标识不能为空");
        }
        this.workerId = workerId;
    }

    @Scheduled(
            fixedDelayString = "${rag.ingestion.worker.poll-delay:PT1S}",
            initialDelayString = "${rag.ingestion.worker.poll-delay:PT1S}")
    void scheduledDrain() {
        drainAvailable();
    }

    public int drainAvailable() {
        if (!draining.compareAndSet(false, true)) return 0;
        try {
            return runOnce.getAsBoolean() ? 1 : 0;
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "解析 Worker 周期失败 [workerId=" + workerId + "]", failure);
            return 0;
        } finally {
            draining.set(false);
        }
    }

    public String workerId() {
        return workerId;
    }
}
