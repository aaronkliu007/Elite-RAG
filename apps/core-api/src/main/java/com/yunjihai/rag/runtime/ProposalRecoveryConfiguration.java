package com.yunjihai.rag.runtime;

import com.yunjihai.rag.solution.proposal.ProposalAttemptStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Profile("local")
@EnableScheduling
public class ProposalRecoveryConfiguration {
    @Bean
    @ConditionalOnProperty(name = {"rag.workers.enabled", "rag.solution.proposals.recovery.enabled"},
            havingValue = "true")
    ProposalRecoveryScheduler proposalRecoveryScheduler(ProposalAttemptStore attempts,
            @Value("${rag.solution.proposals.recovery.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException("invalid proposal recovery batch size");
        return new ProposalRecoveryScheduler(() -> attempts.expireStale(batchSize));
    }
}
