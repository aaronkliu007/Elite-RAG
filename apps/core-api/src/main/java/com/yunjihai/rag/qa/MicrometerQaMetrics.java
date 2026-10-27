package com.yunjihai.rag.qa;

import com.yunjihai.rag.qa.domain.QaOutcome;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import com.yunjihai.rag.qa.model.ModelUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;

public final class MicrometerQaMetrics implements QaMetrics {
    private final MeterRegistry registry;
    private final double inputCostPerMillionTokens;
    private final double outputCostPerMillionTokens;

    public MicrometerQaMetrics(
            MeterRegistry registry,
            double inputCostPerMillionTokens,
            double outputCostPerMillionTokens) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.inputCostPerMillionTokens = requireRate(inputCostPerMillionTokens);
        this.outputCostPerMillionTokens = requireRate(outputCostPerMillionTokens);
    }

    @Override
    public void recordStage(Stage stage, Duration duration) {
        Timer.builder("rag.qa.stage.duration")
                .tag("stage", Objects.requireNonNull(stage).name())
                .register(registry)
                .record(requireDuration(duration));
    }

    @Override
    public void recordOutcome(QaOutcome outcome, Duration duration) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Counter.builder("rag.qa.requests")
                .tag("outcome", outcome.status().name())
                .tag("degraded", Boolean.toString(degraded(outcome)))
                .register(registry)
                .increment();
        Timer.builder("rag.qa.duration")
                .tag("outcome", outcome.status().name())
                .register(registry)
                .record(requireDuration(duration));
    }

    @Override
    public void recordModelUsage(ModelUsage usage) {
        Objects.requireNonNull(usage, "usage must not be null");
        Counter.builder("rag.qa.model.tokens").tag("direction", "INPUT")
                .register(registry).increment(usage.inputTokens());
        Counter.builder("rag.qa.model.tokens").tag("direction", "OUTPUT")
                .register(registry).increment(usage.outputTokens());
        Timer.builder("rag.qa.model.latency").register(registry).record(usage.latency());
        double estimatedCost = (usage.inputTokens() * inputCostPerMillionTokens
                + usage.outputTokens() * outputCostPerMillionTokens) / 1_000_000.0;
        Counter.builder("rag.qa.model.cost.estimated").register(registry).increment(estimatedCost);
    }

    @Override
    public void recordSanitizationHits(int count) {
        if (count < 0) throw new IllegalArgumentException("sanitization count must not be negative");
        Counter.builder("rag.qa.sanitization.hits").register(registry).increment(count);
    }

    @Override
    public void recordModelError(ModelGatewayException.Kind kind) {
        Counter.builder("rag.qa.model.errors")
                .tag("kind", Objects.requireNonNull(kind).name())
                .register(registry)
                .increment();
    }

    private static boolean degraded(QaOutcome outcome) {
        if (outcome instanceof QaOutcome.Answered answered) return answered.degraded();
        if (outcome instanceof QaOutcome.Refused refused) return refused.degraded();
        return ((QaOutcome.Failed) outcome).degraded();
    }

    private static double requireRate(double rate) {
        if (!Double.isFinite(rate) || rate < 0.0) {
            throw new IllegalArgumentException("model token cost rate must be finite and non-negative");
        }
        return rate;
    }

    private static Duration requireDuration(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isNegative()) throw new IllegalArgumentException("duration must not be negative");
        return duration;
    }
}
