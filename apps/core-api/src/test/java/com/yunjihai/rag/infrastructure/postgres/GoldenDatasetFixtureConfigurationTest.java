package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.qa.evaluation.EvaluationDataset;
import com.yunjihai.rag.retrieval.projection.ProjectionEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenDatasetFixtureConfigurationTest {
    private static final DriverManagerDataSource DATA_SOURCE = new DriverManagerDataSource();
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=local")
            .withBean(JdbcClient.class, () -> JdbcClient.create(DATA_SOURCE))
            .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(DATA_SOURCE))
            .withBean(ProjectionEventRepository.class, GoldenDatasetFixtureConfigurationTest::unusedEvents)
            .withBean(EvaluationDataset.class, () -> new EvaluationDataset(
                    UUID.randomUUID(), "test-v1", "prompt-v1", "sanitization-v1", List.of(), false))
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(GoldenDatasetFixtureConfiguration.class);

    @Test
    void fixtureIsDisabledByDefault() {
        context.run(result -> assertThat(result)
                .doesNotHaveBean(JdbcGoldenDatasetFixtureProvisioner.class));
    }

    @Test
    void fixtureCanBeEnabledExplicitlyForLocalEvaluation() {
        context.withPropertyValues("rag.qa.evaluation.fixture.enabled=true")
                .run(result -> assertThat(result)
                        .hasSingleBean(JdbcGoldenDatasetFixtureProvisioner.class));
    }

    @Test
    void fixtureCannotBeEnabledOutsideLocalProfile() {
        context.withPropertyValues(
                        "spring.profiles.active=test",
                        "rag.qa.evaluation.fixture.enabled=true")
                .run(result -> assertThat(result)
                        .doesNotHaveBean(JdbcGoldenDatasetFixtureProvisioner.class));
    }

    private static ProjectionEventRepository unusedEvents() {
        return (ProjectionEventRepository) Proxy.newProxyInstance(
                ProjectionEventRepository.class.getClassLoader(),
                new Class<?>[]{ProjectionEventRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass().equals(Object.class)) {
                        return switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            case "toString" -> "unused ProjectionEventRepository";
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    }
                    throw new UnsupportedOperationException("configuration test must not use the event store");
                });
    }
}
