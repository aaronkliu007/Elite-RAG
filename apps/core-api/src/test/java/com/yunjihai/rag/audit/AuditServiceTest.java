package com.yunjihai.rag.audit;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T11:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000811");
    private static final UUID RESOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000812");

    @Test
    void appendsEveryRequiredKnowledgeAction() {
        var events = new ArrayList<AuditEvent>();
        var service = new AuditService(events::add, Clock.fixed(NOW, ZoneOffset.UTC));

        for (var action : AuditAction.values()) {
            service.record(new AuditCommand(
                    ACTOR_ID,
                    action,
                    "DOCUMENT",
                    RESOURCE_ID,
                    AuditOutcome.SUCCEEDED,
                    Map.of("versionId", RESOURCE_ID.toString())));
        }

        assertThat(events).extracting(AuditEvent::action).containsExactly(AuditAction.values());
        assertThat(events).allSatisfy(event -> {
            assertThat(event.occurredAt()).isEqualTo(NOW);
            assertThat(event.metadata()).doesNotContainKeys("body", "text", "content");
        });
    }

    @Test
    void rejectsSensitiveBodyMetadataBeforeAppend() {
        var events = new ArrayList<AuditEvent>();
        var service = new AuditService(events::add, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.record(new AuditCommand(
                ACTOR_ID,
                AuditAction.EDIT,
                "DOCUMENT",
                RESOURCE_ID,
                AuditOutcome.SUCCEEDED,
                Map.of("body", "客户机密正文"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("敏感");

        assertThat(events).isEmpty();
    }

    @Test
    void auditServiceCanBeProxiedAndRunsInsideTransaction() {
        try (var context = new AnnotationConfigApplicationContext(TransactionConfig.class)) {
            var service = context.getBean(AuditService.class);
            var transactions = context.getBean(TrackingTransactionManager.class);

            service.record(new AuditCommand(
                    ACTOR_ID,
                    AuditAction.VIEW,
                    "DOCUMENT",
                    RESOURCE_ID,
                    AuditOutcome.SUCCEEDED,
                    Map.of()));

            assertThat(transactions.began).isTrue();
            assertThat(transactions.committed).isTrue();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfig {

        @Bean
        TrackingTransactionManager transactionManager() {
            return new TrackingTransactionManager();
        }

        @Bean
        AuditEventStore auditEventStore() {
            return event -> { };
        }

        @Bean
        AuditService auditService(AuditEventStore store) {
            return new AuditService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }

    static final class TrackingTransactionManager extends AbstractPlatformTransactionManager {
        private boolean began;
        private boolean committed;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            began = true;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            committed = true;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
