package com.yunjihai.rag.infrastructure.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.audit.AuditEvent;
import com.yunjihai.rag.audit.AuditEventStore;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** Insert-only adapter for audit events already validated by AuditService. */
public final class JdbcAuditEventStore implements AuditEventStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcClient jdbc;

    public JdbcAuditEventStore(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void append(AuditEvent event) {
        Objects.requireNonNull(event);
        String metadata;
        try {
            metadata = JSON.writeValueAsString(event.metadata());
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("AUDIT_METADATA_SERIALIZATION_FAILURE");
        }
        try {
            jdbc.sql("""
                    INSERT INTO audit_event (id, actor_id, action, resource_type, resource_id, result, metadata, occurred_at)
                    VALUES (:id, :actorId, :action, :resourceType, :resourceId, :result, CAST(:metadata AS jsonb), :occurredAt)
                    """)
                    .param("id", event.id())
                    .param("actorId", event.actorId())
                    .param("action", event.action().name())
                    .param("resourceType", event.resourceType())
                    .param("resourceId", event.resourceId())
                    .param("result", event.outcome().name())
                    .param("metadata", metadata)
                    .param("occurredAt", OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                    .update();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }
}
