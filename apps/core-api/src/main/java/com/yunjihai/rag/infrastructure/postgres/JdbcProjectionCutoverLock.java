package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.retrieval.projection.ProjectionCutoverLock;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** Shared lock for ordinary event writes and exclusive lock for alias cutover. */
public final class JdbcProjectionCutoverLock implements ProjectionCutoverLock {
    private static final long CUTOVER_LOCK_KEY = 0x5241474355544f56L;
    private final JdbcClient jdbc;

    public JdbcProjectionCutoverLock(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public <T> T withShared(Supplier<T> action) {
        Objects.requireNonNull(action);
        requireTransaction();
        try {
            jdbc.sql("SELECT pg_advisory_xact_lock_shared(:lockKey)")
                    .param("lockKey", CUTOVER_LOCK_KEY)
                    .query((row, number) -> Boolean.TRUE)
                    .single();
            return action.get();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public <T> T withExclusive(Duration timeout, Supplier<T> action) {
        Objects.requireNonNull(timeout);
        Objects.requireNonNull(action);
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("投影切换锁超时时间无效");
        }
        requireTransaction();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!tryExclusive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("PROJECTION_CUTOVER_LOCK_TIMEOUT");
                sleep(Math.min(Duration.ofMillis(25).toNanos(), remaining));
            }
            return action.get();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private boolean tryExclusive() {
        return jdbc.sql("SELECT pg_try_advisory_xact_lock(:lockKey)")
                .param("lockKey", CUTOVER_LOCK_KEY)
                .query(Boolean.class)
                .single();
    }

    private static void sleep(long nanos) {
        try {
            Thread.sleep(Duration.ofNanos(nanos));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PROJECTION_CUTOVER_LOCK_INTERRUPTED", failure);
        }
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("PROJECTION_CUTOVER_LOCK_REQUIRES_TRANSACTION");
        }
    }
}
