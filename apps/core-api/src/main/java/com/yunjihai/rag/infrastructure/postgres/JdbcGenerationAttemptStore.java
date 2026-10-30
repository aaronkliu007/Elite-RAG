package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.solution.generation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class JdbcGenerationAttemptStore implements GenerationAttemptStore {
    private final JdbcClient jdbc;

    public JdbcGenerationAttemptStore(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Instant authoritativeNow(Instant fallback) {
        Objects.requireNonNull(fallback);
        try {
            return jdbc.sql("SELECT statement_timestamp()")
                    .query(OffsetDateTime.class).single().toInstant();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public Optional<GenerationAttempt> findByClientKey(String clientKey) {
        Objects.requireNonNull(clientKey);
        try {
            return jdbc.sql("SELECT * FROM solution_generation_attempt WHERE client_key = :clientKey")
                    .param("clientKey", clientKey).query((row, ignored) -> {
                        var key = new GenerationKey(row.getString("client_key"),
                                row.getObject("solution_id", UUID.class),
                                row.getObject("requirement_version_id", UUID.class),
                                row.getObject("outline_version_id", UUID.class),
                                row.getObject("section_id", UUID.class),
                                Optional.ofNullable(row.getObject("baseline_section_version_id", UUID.class)));
                        var inputTokens = row.getObject("model_input_tokens", Long.class);
                        Optional<ModelUsage> usage = inputTokens == null ? Optional.empty()
                                : Optional.of(new ModelUsage(inputTokens,
                                row.getLong("model_output_tokens"), row.getLong("model_cost_micros"),
                                row.getLong("model_latency_millis")));
                        return new GenerationAttempt(
                                row.getObject("id", UUID.class), key, row.getString("input_fingerprint"),
                                GenerationStatus.valueOf(row.getString("status")),
                                row.getInt("execution_count"), row.getLong("revision"),
                                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                                row.getObject("updated_at", OffsetDateTime.class).toInstant(),
                                Optional.ofNullable(row.getObject("retrieval_request_id", UUID.class)),
                                evidenceIds(row.getArray("evidence_ids")), usage,
                                Optional.ofNullable(row.getObject("result_section_version_id", UUID.class)),
                                Optional.ofNullable(row.getString("failure_code")),
                                Optional.ofNullable(row.getString("retry_checkpoint")).map(GenerationStatus::valueOf));
                    }).optional();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public GenerationAttemptRegistration register(GenerationAttempt attempt) {
        Objects.requireNonNull(attempt);
        if (attempt.status() != GenerationStatus.PENDING || attempt.revision() != 0) {
            throw new IllegalArgumentException("only a new pending attempt can be registered");
        }
        try {
            var inserted = jdbc.sql("""
                    INSERT INTO solution_generation_attempt
                        (id, client_key, solution_id, requirement_version_id, outline_version_id,
                         section_id, baseline_section_version_id, input_fingerprint, status,
                         execution_count, revision, created_at, updated_at)
                    VALUES (:id, :clientKey, :solutionId, :requirementId, :outlineId,
                            :sectionId, :baselineId, :fingerprint, :status,
                            :executionCount, :revision, :createdAt, :updatedAt)
                    ON CONFLICT (client_key) DO NOTHING RETURNING id
                    """).param("id", attempt.id()).param("clientKey", attempt.key().clientKey())
                    .param("solutionId", attempt.key().solutionId())
                    .param("requirementId", attempt.key().requirementVersionId())
                    .param("outlineId", attempt.key().outlineVersionId())
                    .param("sectionId", attempt.key().sectionId())
                    .param("baselineId", attempt.key().baselineSectionVersionId().orElse(null))
                    .param("fingerprint", attempt.inputFingerprint())
                    .param("status", attempt.status().name())
                    .param("executionCount", attempt.executionCount())
                    .param("revision", attempt.revision())
                    .param("createdAt", at(attempt.createdAt()))
                    .param("updatedAt", at(attempt.updatedAt()))
                    .query(UUID.class).optional();
            if (inserted.isPresent()) {
                return new GenerationAttemptRegistration(attempt, true);
            }
            var existing = findByClientKey(attempt.key().clientKey())
                    .orElseThrow(GenerationStateException::new);
            existing.validateRequest(attempt.key());
            return new GenerationAttemptRegistration(existing, false);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public void replace(GenerationAttempt next, long expectedRevision) {
        Objects.requireNonNull(next);
        if (expectedRevision < 0 || next.revision() != expectedRevision + 1) {
            throw new GenerationStateException();
        }
        try {
            var usage = next.modelUsage();
            var updated = jdbc.sql("""
                    UPDATE solution_generation_attempt SET
                        status = :status, execution_count = :executionCount,
                        revision = :revision, updated_at = :updatedAt,
                        retrieval_request_id = :retrievalRequestId,
                        evidence_ids = CAST(:evidenceIds AS uuid[]),
                        model_input_tokens = :inputTokens,
                        model_output_tokens = :outputTokens,
                        model_cost_micros = :costMicros,
                        model_latency_millis = :latencyMillis,
                        result_section_version_id = :resultSectionVersionId,
                        failure_code = :failureCode, retry_checkpoint = :retryCheckpoint
                    WHERE id = :id AND revision = :expectedRevision
                      AND client_key = :clientKey AND input_fingerprint = :fingerprint
                    """).param("status", next.status().name())
                    .param("executionCount", next.executionCount())
                    .param("revision", next.revision())
                    .param("updatedAt", at(next.updatedAt()))
                    .param("retrievalRequestId", next.retrievalRequestId().orElse(null))
                    .param("evidenceIds", evidenceArray(next.evidenceIds()))
                    .param("inputTokens", usage.map(ModelUsage::inputTokens).orElse(null))
                    .param("outputTokens", usage.map(ModelUsage::outputTokens).orElse(null))
                    .param("costMicros", usage.map(ModelUsage::costMicros).orElse(null))
                    .param("latencyMillis", usage.map(ModelUsage::latencyMillis).orElse(null))
                    .param("resultSectionVersionId", next.resultSectionVersionId().orElse(null))
                    .param("failureCode", next.failureCode().orElse(null))
                    .param("retryCheckpoint", next.retryCheckpoint().map(Enum::name).orElse(null))
                    .param("id", next.id()).param("expectedRevision", expectedRevision)
                    .param("clientKey", next.key().clientKey())
                    .param("fingerprint", next.inputFingerprint()).update();
            if (updated != 1) {
                throw new GenerationStateException();
            }
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    /** Expires a bounded set of abandoned pre-verification attempts using the database clock. */
    public int expireStale(Duration recoveryAfter, int batchSize) {
        Objects.requireNonNull(recoveryAfter, "recoveryAfter");
        long leaseMillis = recoveryAfter.toMillis();
        if (leaseMillis < 1 || batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("invalid generation recovery scan limits");
        }
        try {
            return jdbc.sql("""
                    WITH expired AS (
                        SELECT id FROM solution_generation_attempt
                        WHERE status IN ('PENDING', 'RETRIEVING', 'GENERATING')
                          AND updated_at <= statement_timestamp()
                              - CAST(:leaseMillis AS bigint) * INTERVAL '1 millisecond'
                        ORDER BY updated_at, id
                        LIMIT :batchSize
                        FOR UPDATE SKIP LOCKED
                    )
                    UPDATE solution_generation_attempt AS attempt SET
                        status = 'FAILED_RETRYABLE',
                        retry_checkpoint = attempt.status,
                        failure_code = 'WORKER_LEASE_EXPIRED',
                        revision = attempt.revision + 1,
                        updated_at = statement_timestamp()
                    FROM expired WHERE attempt.id = expired.id
                    """).param("leaseMillis", leaseMillis)
                    .param("batchSize", batchSize).update();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private static Set<UUID> evidenceIds(java.sql.Array value) throws SQLException {
        return Arrays.stream((UUID[]) value.getArray()).collect(Collectors.toUnmodifiableSet());
    }

    private static String evidenceArray(Set<UUID> ids) {
        return ids.stream().sorted().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
