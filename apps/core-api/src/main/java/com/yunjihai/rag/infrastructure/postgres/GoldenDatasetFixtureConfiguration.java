package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.qa.evaluation.EvaluationDataset;
import com.yunjihai.rag.retrieval.projection.ProjectionEventRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(name = "rag.qa.evaluation.fixture.enabled", havingValue = "true")
class GoldenDatasetFixtureConfiguration {
    @Bean
    JdbcGoldenDatasetFixtureProvisioner goldenDatasetFixtureProvisioner(
            JdbcClient jdbc,
            PlatformTransactionManager transactions,
            ProjectionEventRepository projectionEvents,
            EvaluationDataset dataset,
            Clock clock) {
        return new JdbcGoldenDatasetFixtureProvisioner(
                jdbc, new TransactionTemplate(transactions), projectionEvents, dataset, clock);
    }
}
