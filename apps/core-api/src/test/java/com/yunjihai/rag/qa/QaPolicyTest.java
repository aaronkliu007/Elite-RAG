package com.yunjihai.rag.qa;

import com.yunjihai.rag.qa.domain.RefusalReason;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QaPolicyTest {

    @Test
    void defaults_pin_security_cost_and_quality_limits() {
        var policy = QaPolicy.defaults();

        assertThat(policy.maxQuestionCodePoints()).isEqualTo(2_000);
        assertThat(policy.maxAuthorizedSpaces()).isEqualTo(100);
        assertThat(policy.requestedEvidenceCount()).isEqualTo(10);
        assertThat(policy.minEvidenceCount()).isEqualTo(8);
        assertThat(policy.maxEvidenceCount()).isEqualTo(15);
        assertThat(policy.maxEvidenceCodePoints()).isEqualTo(1_200);
        assertThat(policy.maxModelContextCodePoints()).isEqualTo(12_000);
        assertThat(policy.maxModelOutputCodePoints()).isEqualTo(2_000);
        assertThat(policy.totalTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void rejects_inconsistent_or_unbounded_policy() {
        assertThatThrownBy(() -> new QaPolicy(
                2_000, 100, 7, 8, 15, 1_200, 12_000, 2_000,
                Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QaPolicy(
                2_000, 101, 10, 8, 15, 1_200, 12_000, 2_000,
                Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QaPolicy(
                2_000, 100, 10, 8, 15, 1_200, 12_000, 2_000,
                Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusal_reasons_cover_all_strict_fail_closed_decisions() {
        assertThat(RefusalReason.values()).contains(
                RefusalReason.NO_AUTHORIZED_SPACE,
                RefusalReason.INSUFFICIENT_EVIDENCE,
                RefusalReason.CONFLICTING_EVIDENCE,
                RefusalReason.UNSAFE_TO_SEND,
                RefusalReason.INVALID_MODEL_OUTPUT,
                RefusalReason.UNSUPPORTED_CLAIM);
    }
}
