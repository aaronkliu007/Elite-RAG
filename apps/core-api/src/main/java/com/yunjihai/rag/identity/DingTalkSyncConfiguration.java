package com.yunjihai.rag.identity;

import com.yunjihai.rag.infrastructure.postgres.JdbcOrganizationReconciliationAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rag.dingtalk", name = "enabled", havingValue = "true")
public class DingTalkSyncConfiguration {
    @Bean
    DingTalkOrganizationSynchronizer dingTalkOrganizationSynchronizer(
            JdbcClient jdbc, SessionRevocationService sessions, PlatformTransactionManager transactions, Clock clock,
            @Value("${rag.dingtalk.client-id:}") String clientId,
            @Value("${rag.dingtalk.client-secret:}") String clientSecret,
            @Value("${rag.dingtalk.authoritative-scope-confirmed:false}") boolean confirmed) {
        var client = new DingTalkDirectoryHttpClient(URI.create("https://api.dingtalk.com"),
                URI.create("https://oapi.dingtalk.com"), new DingTalkCredentials(clientId, clientSecret), Duration.ofSeconds(10));
        var collector = new DingTalkOrganizationCollector(client, clock);
        var sync = new OrganizationSyncService(new JdbcOrganizationReconciliationAdapter(jdbc, "dingtalk:"), sessions);
        var transaction = new TransactionTemplate(transactions);
        return new DingTalkOrganizationSynchronizer(collector,
                snapshot -> transaction.executeWithoutResult(status -> sync.synchronize(snapshot)), confirmed);
    }
}
