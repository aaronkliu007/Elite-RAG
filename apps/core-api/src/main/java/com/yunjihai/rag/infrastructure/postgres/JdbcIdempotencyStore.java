package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.api.idempotency.IdempotencyScope;
import com.yunjihai.rag.api.idempotency.IdempotencyStore;
import com.yunjihai.rag.api.idempotency.StoredHttpResult;
import com.yunjihai.rag.common.UuidV7;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

public class JdbcIdempotencyStore implements IdempotencyStore {
    private final JdbcClient jdbc;

    public JdbcIdempotencyStore(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    // Commit the claim before the business transaction so concurrent callers observe IN_PROGRESS.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(IdempotencyScope scope, String requestHash, Instant now, Instant expiresAt) {
        requireClaim(scope, requestHash, now, expiresAt);
        try {
            jdbc.sql("""
                            DELETE FROM idempotency_record
                            WHERE actor_id = :actorId AND operation = :operation
                              AND idempotency_key = :key AND expires_at <= :now
                            """)
                    .param("actorId", scope.actorId())
                    .param("operation", scope.operation())
                    .param("key", scope.key())
                    .param("now", timestamp(now))
                    .update();
            int inserted = jdbc.sql("""
                            INSERT INTO idempotency_record
                                (id, actor_id, operation, idempotency_key, request_hash,
                                 status, created_at, updated_at, expires_at)
                            VALUES
                                (:id, :actorId, :operation, :key, :requestHash,
                                 'IN_PROGRESS', :now, :now, :expiresAt)
                            ON CONFLICT (actor_id, operation, idempotency_key) DO NOTHING
                            """)
                    .param("id", UuidV7.generate(now))
                    .param("actorId", scope.actorId())
                    .param("operation", scope.operation())
                    .param("key", scope.key())
                    .param("requestHash", requestHash)
                    .param("now", timestamp(now))
                    .param("expiresAt", timestamp(expiresAt))
                    .update();
            if (inserted == 1) return new Claim.Acquired();
            return jdbc.sql("""
                            SELECT request_hash, status, http_status, response_body::text AS response_body
                            FROM idempotency_record
                            WHERE actor_id = :actorId AND operation = :operation AND idempotency_key = :key
                            """)
                    .param("actorId", scope.actorId())
                    .param("operation", scope.operation())
                    .param("key", scope.key())
                    .query((row, ignored) -> existingClaim(
                            requestHash,
                            row.getString("request_hash"),
                            row.getString("status"),
                            row.getObject("http_status", Integer.class),
                            row.getString("response_body")))
                    .single();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    @Transactional
    public void complete(
            IdempotencyScope scope, String requestHash, StoredHttpResult result, Instant at) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(requestHash);
        Objects.requireNonNull(result);
        Objects.requireNonNull(at);
        try {
            int updated = jdbc.sql("""
                            UPDATE idempotency_record
                            SET status = 'COMPLETED', http_status = :httpStatus,
                                response_body = CAST(:responseBody AS jsonb), updated_at = :at
                            WHERE actor_id = :actorId AND operation = :operation
                              AND idempotency_key = :key AND request_hash = :requestHash
                              AND status = 'IN_PROGRESS'
                            """)
                    .param("httpStatus", result.status())
                    .param("responseBody", result.responseBody())
                    .param("at", timestamp(at))
                    .param("actorId", scope.actorId())
                    .param("operation", scope.operation())
                    .param("key", scope.key())
                    .param("requestHash", requestHash)
                    .update();
            if (updated != 1) throw new IllegalStateException("idempotency claim is no longer active");
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    // Release must survive rollback of a failed business transaction so a safe retry can acquire it.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(IdempotencyScope scope, String requestHash) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(requestHash);
        try {
            jdbc.sql("""
                            DELETE FROM idempotency_record
                            WHERE actor_id = :actorId AND operation = :operation
                              AND idempotency_key = :key AND request_hash = :requestHash
                              AND status = 'IN_PROGRESS'
                            """)
                    .param("actorId", scope.actorId())
                    .param("operation", scope.operation())
                    .param("key", scope.key())
                    .param("requestHash", requestHash)
                    .update();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private static Claim existingClaim(
            String expectedHash,
            String storedHash,
            String status,
            Integer httpStatus,
            String responseBody) {
        if (!expectedHash.equals(storedHash)) return new Claim.HashMismatch();
        if ("IN_PROGRESS".equals(status)) return new Claim.InProgress();
        if ("COMPLETED".equals(status) && httpStatus != null && responseBody != null) {
            return new Claim.Completed(new StoredHttpResult(httpStatus, responseBody));
        }
        throw new IllegalStateException("invalid idempotency record state");
    }

    private static void requireClaim(
            IdempotencyScope scope, String requestHash, Instant now, Instant expiresAt) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(requestHash);
        Objects.requireNonNull(now);
        Objects.requireNonNull(expiresAt);
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("expiresAt must be after now");
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
