package com.yunjihai.rag.runtime;

import com.yunjihai.rag.solution.proposal.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class ProposalRecoveryConfigurationTest {
    private final AtomicInteger batch = new AtomicInteger();
    private final CountDownLatch scanned = new CountDownLatch(1);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(ProposalRecoveryConfiguration.class)
            .withPropertyValues("spring.profiles.active=local")
            .withBean(ProposalAttemptStore.class, () -> new ProposalAttemptStore() {
                public ProposalRegistration register(ProposalRegistrationRequest request, Duration lease) { throw new AssertionError(); }
                public Optional<ProposalAttempt> findByKey(ProposalKey key) { throw new AssertionError(); }
                public Optional<ProposalAttempt> findCurrent(ProposalRegistrationRequest request) { throw new AssertionError(); }
                public boolean succeed(ProposalRegistrationRequest request, String json) { throw new AssertionError(); }
                public boolean fail(ProposalRegistrationRequest request, String code) { throw new AssertionError(); }
                public int expireStale(int size) { batch.set(size); scanned.countDown(); return 3; }
            });

    @Test void requiresBothSwitchesAndPassesConfiguredBatchToStore() {
        context.run(app -> assertThat(app).doesNotHaveBean(ProposalRecoveryScheduler.class));
        context.withPropertyValues("rag.workers.enabled=true", "rag.solution.proposals.recovery.enabled=false")
                .run(app -> assertThat(app).doesNotHaveBean(ProposalRecoveryScheduler.class));
        context.withPropertyValues("rag.workers.enabled=false", "rag.solution.proposals.recovery.enabled=true")
                .run(app -> assertThat(app).doesNotHaveBean(ProposalRecoveryScheduler.class));
        context.withPropertyValues("rag.workers.enabled=true", "rag.solution.proposals.recovery.enabled=true",
                "rag.solution.proposals.recovery.batch-size=17").run(app -> {
                    assertThat(app).hasNotFailed().hasSingleBean(ProposalRecoveryScheduler.class);
                    assertThat(app.getBean(ProposalRecoveryScheduler.class).scanOnce()).isEqualTo(3);
                    assertThat(batch).hasValue(17);
                });
    }

    @Test void rejectsUnboundedBatchSize() {
        for (int size : new int[]{0, 501}) context.withPropertyValues("rag.workers.enabled=true",
                "rag.solution.proposals.recovery.enabled=true", "rag.solution.proposals.recovery.batch-size=" + size)
                .run(app -> assertThat(app).hasFailed());
    }

    @Test void enabledScheduleInvokesTheDatabaseScanAutomatically() {
        context.withPropertyValues("rag.workers.enabled=true", "rag.solution.proposals.recovery.enabled=true",
                "rag.solution.proposals.recovery.poll-delay=PT0.05S").run(app -> {
                    assertThat(app).hasNotFailed();
                    try { assertThat(scanned.await(3, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                    assertThat(batch).hasValue(100);
                });
    }
}
