package com.yunjihai.rag.runtime;

import com.yunjihai.rag.qa.conflict.EvidenceConflictDetector;
import com.yunjihai.rag.qa.conflict.EvidenceConflictDetectorAutoConfiguration;
import com.yunjihai.rag.qa.conflict.QuestionScopedConflictPolicy;
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

    @Test
    void scopedConflictExtensionRequiresAnExplicitSwitchAndExactlyOneImplementation() {
        var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();
        var policy=new QuestionScopedConflictPolicy() {
            @Override public String version() { return "synthetic-v1"; }
            @Override public Scope classify(com.yunjihai.rag.qa.domain.QaQuestion question,
                    com.yunjihai.rag.qa.conflict.DeterministicEvidenceConflictDetector.ConflictPair pair,
                    java.util.List<com.yunjihai.rag.retrieval.domain.Evidence> candidates) { return Scope.UNRELATED; }
            @Override public AnswerRelevance verifyAnswer(com.yunjihai.rag.qa.domain.QaQuestion question,
                    com.yunjihai.rag.qa.domain.CandidateAnswer answer,com.yunjihai.rag.qa.domain.EvidenceBundle evidence) {
                return AnswerRelevance.RELEVANT;
            }
        };
        var provider=factory.getBeanProvider(QuestionScopedConflictPolicy.class);
        assertThat(WorkerRuntimeConfiguration.selectScopedPolicy(true,provider).enabled()).isFalse();
        factory.addBean("one",policy);
        assertThat(WorkerRuntimeConfiguration.selectScopedPolicy(false,provider).enabled()).isFalse();
        assertThat(WorkerRuntimeConfiguration.selectScopedPolicy(true,provider)).isSameAs(policy);
        factory.addBean("two",policy);
        assertThat(WorkerRuntimeConfiguration.selectScopedPolicy(true,provider).enabled()).isFalse();
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomConflictDetectorConfiguration {
        @Bean
        EvidenceConflictDetector customConflictDetector() {
            return CUSTOM_DETECTOR;
        }
    }
}
