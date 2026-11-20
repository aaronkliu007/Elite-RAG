package com.yunjihai.rag.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
import static org.assertj.core.api.Assertions.assertThat;

class DingTalkSyncConfigurationTest {
    @Test void enabledConfigurationBuildsClientWithoutContactingUpstream() {
        var source = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/unused", "unused", "unused");
        new ApplicationContextRunner().withUserConfiguration(DingTalkSyncConfiguration.class)
                .withBean(JdbcClient.class, () -> JdbcClient.create(source))
                .withBean(SessionRevocationService.class, () -> new SessionRevocationService(new InMemorySessionVersionStore()))
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source))
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues("rag.dingtalk.enabled=true", "rag.dingtalk.client-id=test-id", "rag.dingtalk.client-secret=test-secret")
                .run(context -> assertThat(context).hasSingleBean(DingTalkOrganizationSynchronizer.class));
    }
}
