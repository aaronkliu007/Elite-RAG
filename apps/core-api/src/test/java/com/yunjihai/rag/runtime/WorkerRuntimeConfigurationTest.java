package com.yunjihai.rag.runtime;

import com.yunjihai.rag.qa.conflict.EvidenceConflictDetector;
import com.yunjihai.rag.qa.conflict.EvidenceConflictDetectorAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerRuntimeConfigurationTest {
    private static final EvidenceConflictDetector CUSTOM_DETECTOR = ignored -> false;

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withPropertyValues(
                    "spring.profiles.active=local")
            .withConfiguration(AutoConfigurations.of(EvidenceConflictDetectorAutoConfiguration.class));

    @Test
    void registersTheDeterministicDetectorByDefault() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).hasSingleBean(EvidenceConflictDetector.class);
        });
    }

    @Test
    void defaultConflictDetectorBacksOffWhenDeploymentProvidesACustomImplementation() {
        context.withUserConfiguration(CustomConflictDetectorConfiguration.class).run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).hasSingleBean(EvidenceConflictDetector.class);
            assertThat(application.getBean(EvidenceConflictDetector.class)).isSameAs(CUSTOM_DETECTOR);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomConflictDetectorConfiguration {
        @Bean
        EvidenceConflictDetector customConflictDetector() {
            return CUSTOM_DETECTOR;
        }
    }
}
