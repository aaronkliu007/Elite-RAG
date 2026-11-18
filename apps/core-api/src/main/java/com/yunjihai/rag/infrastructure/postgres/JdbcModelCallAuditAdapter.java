package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.qa.ModelCallAuditPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

public final class JdbcModelCallAuditAdapter implements ModelCallAuditPort {
    private final JdbcClient jdbc;

    public JdbcModelCallAuditAdapter(JdbcClient jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override
    public void record(ModelCallAudit audit) {
        Objects.requireNonNull(audit, "audit must not be null");
        try {
            jdbc.sql("""
                    INSERT INTO qa_model_call_audit(
                        request_id,provider,endpoint_identifier,model_name,model_config_version,
                        request_digest,response_digest,input_tokens,output_tokens,
                        latency_ms,sanitization_hit_count,retry_count,result_code,occurred_at,qa_policy_version)
                    VALUES (:requestId,:provider,:endpointIdentifier,:modelName,:modelConfigVersion,
                        :requestDigest,:responseDigest,:inputTokens,:outputTokens,
                        :latencyMs,:hits,:retries,:resultCode,:occurredAt,:qaPolicyVersion)
                    """)
                    .param("requestId", audit.requestId())
                    .param("provider", audit.provider())
                    .param("endpointIdentifier", audit.endpointIdentifier())
                    .param("modelName", audit.model())
                    .param("modelConfigVersion", audit.modelConfigVersion())
                    .param("qaPolicyVersion", audit.qaPolicyVersion())
                    .param("requestDigest", audit.requestDigest())
                    .param("responseDigest", audit.responseDigest())
                    .param("inputTokens", audit.usage().inputTokens())
                    .param("outputTokens", audit.usage().outputTokens())
                    .param("latencyMs", audit.usage().latency().toMillis())
                    .param("hits", audit.sanitizationHitCount())
                    .param("retries", audit.retryCount())
                    .param("resultCode", audit.resultCode())
                    .param("occurredAt", OffsetDateTime.ofInstant(audit.occurredAt(), ZoneOffset.UTC))
                    .update();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }
}
