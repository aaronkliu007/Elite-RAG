package com.yunjihai.rag.qa.conflict;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class EvidenceConflictDetectorAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(EvidenceConflictDetector.class)
    EvidenceConflictDetector evidenceConflictDetector() {
        return new DeterministicEvidenceConflictDetector();
    }
}
