package com.yunjihai.rag.runtime;

import com.yunjihai.rag.retrieval.projection.ProjectionWorker;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/** 只使用部署配置目标索引的投影任务轮询器。 */
public final class ProjectionWorkerScheduler {

    private static final System.Logger LOGGER =
            System.getLogger(ProjectionWorkerScheduler.class.getName());

    private final BiFunction<String, String, Integer> runOnce;
    private final String workerId;
    private final String targetIndex;
    private final AtomicBoolean draining = new AtomicBoolean();

    public ProjectionWorkerScheduler(
            ProjectionWorker worker,
            String workerId,
            String targetIndex) {
        this(worker::runOnce, workerId, targetIndex);
    }

    ProjectionWorkerScheduler(
            BiFunction<String, String, Integer> runOnce,
            String workerId,
            String targetIndex) {
        this.runOnce = Objects.requireNonNull(runOnce);
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("投影 Worker 标识不能为空");
        }
        if (targetIndex == null || targetIndex.isBlank()) {
            throw new IllegalArgumentException("投影目标索引不能为空");
        }
        this.workerId = workerId;
        this.targetIndex = targetIndex;
    }

    @Scheduled(
            fixedDelayString = "${rag.opensearch.worker.poll-delay:PT1S}",
            initialDelayString = "${rag.opensearch.worker.poll-delay:PT1S}")
    void scheduledDrain() {
        drainAvailable();
    }

    public int drainAvailable() {
        if (!draining.compareAndSet(false, true)) return 0;
        try {
            return runOnce.apply(workerId, targetIndex);
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "投影 Worker 周期失败 [workerId=" + workerId
                            + ", targetIndex=" + targetIndex + "]",
                    failure);
            return 0;
        } finally {
            draining.set(false);
        }
    }
}
