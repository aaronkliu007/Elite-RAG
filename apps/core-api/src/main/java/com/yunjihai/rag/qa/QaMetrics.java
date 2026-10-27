package com.yunjihai.rag.qa;

import com.yunjihai.rag.qa.domain.QaOutcome;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import com.yunjihai.rag.qa.model.ModelUsage;

import java.time.Duration;

public interface QaMetrics {
    enum Stage {
        AUTHORIZATION,
        RETRIEVAL,
        SANITIZATION,
        GENERATION,
        VERIFICATION
    }

    void recordStage(Stage stage, Duration duration);

    void recordOutcome(QaOutcome outcome, Duration duration);

    void recordModelUsage(ModelUsage usage);

    void recordSanitizationHits(int count);

    void recordModelError(ModelGatewayException.Kind kind);

    static QaMetrics noop() {
        return NoopQaMetrics.INSTANCE;
    }

    enum NoopQaMetrics implements QaMetrics {
        INSTANCE;

        @Override public void recordStage(Stage stage, Duration duration) {}
        @Override public void recordOutcome(QaOutcome outcome, Duration duration) {}
        @Override public void recordModelUsage(ModelUsage usage) {}
        @Override public void recordSanitizationHits(int count) {}
        @Override public void recordModelError(ModelGatewayException.Kind kind) {}
    }
}
