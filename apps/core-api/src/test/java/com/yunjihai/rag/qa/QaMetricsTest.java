package com.yunjihai.rag.qa;

import com.yunjihai.rag.qa.domain.CitationClaim;
import com.yunjihai.rag.qa.domain.QaOutcome;
import com.yunjihai.rag.qa.domain.RefusalReason;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import com.yunjihai.rag.qa.model.ModelUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class QaMetricsTest {

    @Test
    void recordsOnlyBoundedOutcomeStageAndErrorLabels() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerQaMetrics(registry, 2.0, 10.0);

        metrics.recordOutcome(new QaOutcome.Answered(
                id(1), "回答", List.of(new CitationClaim(id(2), "声明", List.of(id(3)))), false),
                Duration.ofMillis(120));
        metrics.recordOutcome(new QaOutcome.Refused(
                id(4), RefusalReason.INSUFFICIENT_EVIDENCE, true), Duration.ofMillis(30));
        metrics.recordOutcome(new QaOutcome.Failed(id(5), "MODEL_TIMEOUT", true),
                Duration.ofMillis(50));
        metrics.recordStage(QaMetrics.Stage.RETRIEVAL, Duration.ofMillis(20));
        metrics.recordModelError(ModelGatewayException.Kind.TIMEOUT);

        assertThat(registry.get("rag.qa.requests")
                .tags("outcome", "ANSWERED", "degraded", "false").counter().count()).isEqualTo(1);
        assertThat(registry.get("rag.qa.requests")
                .tags("outcome", "REFUSED", "degraded", "true").counter().count()).isEqualTo(1);
        assertThat(registry.get("rag.qa.requests")
                .tags("outcome", "FAILED", "degraded", "true").counter().count()).isEqualTo(1);
        assertThat(registry.get("rag.qa.stage.duration")
                .tag("stage", "RETRIEVAL").timer().count()).isEqualTo(1);
        assertThat(registry.get("rag.qa.model.errors")
                .tag("kind", "TIMEOUT").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getValue())
                .allMatch(value -> Set.of(
                        "ANSWERED", "REFUSED", "FAILED", "true", "false", "RETRIEVAL", "TIMEOUT")
                        .contains(value));
    }

    @Test
    void recordsTokenLatencySanitizationAndConfiguredCostEstimateWithoutPayloadTags() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerQaMetrics(registry, 2.0, 10.0);

        metrics.recordModelUsage(new ModelUsage(400, 100, Duration.ofMillis(75)));
        metrics.recordSanitizationHits(3);

        assertThat(registry.get("rag.qa.model.tokens")
                .tag("direction", "INPUT").counter().count()).isEqualTo(400);
        assertThat(registry.get("rag.qa.model.tokens")
                .tag("direction", "OUTPUT").counter().count()).isEqualTo(100);
        assertThat(registry.get("rag.qa.model.latency").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(75);
        assertThat(registry.get("rag.qa.sanitization.hits").counter().count()).isEqualTo(3);
        assertThat(registry.get("rag.qa.model.cost.estimated").counter().count())
                .isCloseTo(0.0018, offset(0.0000001));
        assertThat(registry.getMeters()).flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getValue())
                .doesNotContain("question-secret", "document-secret", "model-response-secret");
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-7000-8000-%012d".formatted(suffix));
    }
}
