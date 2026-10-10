package com.yunjihai.rag.infrastructure.model;

import com.yunjihai.rag.qa.model.ModelGatewayException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.Semaphore;

public final class ModelUsagePolicy {
    private final long dailyTokenLimit;
    private final Semaphore concurrency;
    private final Clock clock;
    private LocalDate usageDate;
    private long usedTokens;

    public ModelUsagePolicy(ApprovedModelGatewayProperties properties) {
        this(properties, Clock.systemUTC());
    }

    ModelUsagePolicy(ApprovedModelGatewayProperties properties, Clock clock) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.dailyTokenLimit = properties.dailyDeploymentTokenLimit();
        this.concurrency = new Semaphore(properties.maxConcurrency(), true);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.usageDate = LocalDate.now(clock);
    }

    public Permit acquire(int estimatedTokens) {
        if (estimatedTokens < 0) throw new ModelGatewayException(ModelGatewayException.Kind.POLICY_VIOLATION);
        if (!concurrency.tryAcquire()) throw new ModelGatewayException(ModelGatewayException.Kind.RATE_LIMITED);
        boolean accepted = false;
        try {
            LocalDate reservationDate;
            synchronized (this) {
                resetIfNewDay();
                if (estimatedTokens > dailyTokenLimit - usedTokens) {
                    throw new ModelGatewayException(ModelGatewayException.Kind.RATE_LIMITED);
                }
                usedTokens += estimatedTokens;
                reservationDate = usageDate;
            }
            accepted = true;
            return new Permit(this, concurrency, reservationDate, estimatedTokens);
        } finally {
            if (!accepted) concurrency.release();
        }
    }

    private void resetIfNewDay() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(usageDate)) {
            usageDate = today;
            usedTokens = 0;
        }
    }

    private synchronized void reconcile(LocalDate reservationDate, int reservedTokens, int actualTokens) {
        if (actualTokens < 0) throw new ModelGatewayException(ModelGatewayException.Kind.POLICY_VIOLATION);
        resetIfNewDay();
        long adjustment;
        if (usageDate.equals(reservationDate)) {
            adjustment = (long) actualTokens - reservedTokens;
        } else {
            adjustment = actualTokens;
        }
        if (adjustment > dailyTokenLimit - usedTokens) {
            usedTokens = dailyTokenLimit;
            throw new ModelGatewayException(ModelGatewayException.Kind.RATE_LIMITED);
        }
        usedTokens = Math.max(0, usedTokens + adjustment);
    }

    public static final class Permit implements AutoCloseable {
        private final ModelUsagePolicy owner;
        private final Semaphore semaphore;
        private final LocalDate reservationDate;
        private final int reservedTokens;
        private boolean closed;
        private boolean completed;

        private Permit(
                ModelUsagePolicy owner,
                Semaphore semaphore,
                LocalDate reservationDate,
                int reservedTokens) {
            this.owner = owner;
            this.semaphore = semaphore;
            this.reservationDate = reservationDate;
            this.reservedTokens = reservedTokens;
        }

        public synchronized void complete(int actualTokens) {
            if (closed || completed) throw new IllegalStateException("model usage permit is no longer active");
            owner.reconcile(reservationDate, reservedTokens, actualTokens);
            completed = true;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                semaphore.release();
            }
        }
    }
}
