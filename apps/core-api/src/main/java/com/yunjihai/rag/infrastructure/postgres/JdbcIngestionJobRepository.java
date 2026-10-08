package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.ingestion.DocumentVersionId;
import com.yunjihai.rag.ingestion.IngestionJobRepository;
import com.yunjihai.rag.ingestion.IngestionLease;
import com.yunjihai.rag.ingestion.IngestionLeaseConflictException;
import com.yunjihai.rag.ingestion.IngestionRequest;
import com.yunjihai.rag.ingestion.IngestionRequestConflictException;
import com.yunjihai.rag.ingestion.ParserError;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL-backed recoverable ingestion queue with token-bound leases. */
public final class JdbcIngestionJobRepository implements IngestionJobRepository {
    private final JdbcClient jdbc;

    public JdbcIngestionJobRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void enqueue(UUID jobId, IngestionRequest request, Instant at) {
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(request);
        Objects.requireNonNull(at);
        if (!request.sha256().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("入库 SHA-256 无效");
        }
        try {
            int inserted = jdbc.sql("""
                    INSERT INTO ingestion_job (
                        id, document_id, version_id, requested_by, source_blob_id, sha256, source_filename,
                        media_type, byte_size, status, attempts, next_attempt_at, created_at, updated_at)
                    SELECT :id, version.document_id, version.id, :requestedBy, :sourceBlobId, :sha256,
                           :sourceFilename, :mediaType, :byteSize, 'QUEUED', 0, :at, :at, :at
                    FROM document_version AS version
                    WHERE version.document_id = :documentId AND version.id = :versionId
                      AND version.source_blob_id = :sourceBlobId
                    """)
                    .param("id", jobId)
                    .param("documentId", request.documentVersion().documentId())
                    .param("versionId", request.documentVersion().versionId())
                    .param("requestedBy", request.actorId())
                    .param("sourceBlobId", request.sourceBlobId())
                    .param("sha256", request.sha256())
                    .param("sourceFilename", request.filename())
                    .param("mediaType", request.mediaType())
                    .param("byteSize", request.sizeBytes())
                    .param("at", utc(micros(at)))
                    .update();
            if (inserted != 1) throw new IngestionRequestConflictException();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public Optional<IngestionLease> claimNext(String workerId, Instant now, Instant leaseUntil) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 255) {
            throw new IllegalArgumentException("Worker 标识无效");
        }
        Objects.requireNonNull(now);
        Objects.requireNonNull(leaseUntil);
        Instant normalizedNow = micros(now);
        Instant normalizedLeaseUntil = micros(leaseUntil);
        if (!normalizedLeaseUntil.isAfter(normalizedNow)) {
            throw new IllegalArgumentException("租约截止时间必须晚于领取时间");
        }
        UUID leaseToken = UUID.randomUUID();
        try {
            return jdbc.sql("""
                    WITH candidate AS (
                        SELECT id
                        FROM ingestion_job
                        WHERE (status IN ('QUEUED', 'RETRY_WAIT') AND next_attempt_at <= :now)
                           OR (status = 'LEASED' AND lease_expires_at <= :now)
                        ORDER BY created_at, id
                        FOR UPDATE SKIP LOCKED
                        LIMIT 1
                    )
                    UPDATE ingestion_job AS job
                    SET status = 'LEASED', attempts = job.attempts + 1,
                        lease_token = :leaseToken, lease_worker = :workerId,
                        lease_expires_at = :leaseUntil, error_code = NULL, updated_at = :now
                    FROM candidate
                    WHERE job.id = candidate.id
                    RETURNING job.id, job.document_id, job.version_id, job.requested_by,
                              job.source_blob_id, job.sha256, job.source_filename, job.media_type, job.byte_size,
                              job.attempts, job.lease_token, job.lease_worker, job.lease_expires_at
                    """)
                    .param("now", utc(normalizedNow))
                    .param("leaseToken", leaseToken)
                    .param("workerId", workerId)
                    .param("leaseUntil", utc(normalizedLeaseUntil))
                    .query(JdbcIngestionJobRepository::mapLease)
                    .optional();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public boolean complete(UUID jobId, UUID leaseToken, Instant at) {
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(leaseToken);
        Objects.requireNonNull(at);
        try {
            int updated = jdbc.sql("""
                    UPDATE ingestion_job
                    SET status = 'SUCCEEDED', lease_token = NULL, lease_worker = NULL,
                        lease_expires_at = NULL, error_code = NULL, updated_at = :at
                    WHERE id = :id AND status = 'LEASED' AND lease_token = :leaseToken
                      AND lease_expires_at > :at
                    """)
                    .param("id", jobId)
                    .param("leaseToken", leaseToken)
                    .param("at", utc(at))
                    .update();
            if (updated == 1) return true;
            var status = jdbc.sql("SELECT status FROM ingestion_job WHERE id = :id")
                    .param("id", jobId)
                    .query(String.class)
                    .optional();
            if (status.filter("SUCCEEDED"::equals).isPresent()) return false;
            throw new IngestionLeaseConflictException();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public void retry(UUID jobId, UUID leaseToken, ParserError error, Instant nextAttemptAt, Instant at) {
        Objects.requireNonNull(error);
        Objects.requireNonNull(nextAttemptAt);
        if (micros(nextAttemptAt).isBefore(micros(at))) {
            throw new IllegalArgumentException("下次尝试时间不能早于当前时间");
        }
        transition(jobId, leaseToken, at, """
                UPDATE ingestion_job
                SET status = 'RETRY_WAIT', next_attempt_at = :nextAttemptAt,
                    lease_token = NULL, lease_worker = NULL, lease_expires_at = NULL,
                    error_code = :errorCode, updated_at = :at
                WHERE id = :id AND status = 'LEASED' AND lease_token = :leaseToken
                  AND lease_expires_at > :at
                """, error, nextAttemptAt);
    }

    @Override
    public void fail(UUID jobId, UUID leaseToken, ParserError error, Instant at) {
        Objects.requireNonNull(error);
        transition(jobId, leaseToken, at, """
                UPDATE ingestion_job
                SET status = 'FAILED', lease_token = NULL, lease_worker = NULL,
                    lease_expires_at = NULL, error_code = :errorCode, updated_at = :at
                WHERE id = :id AND status = 'LEASED' AND lease_token = :leaseToken
                  AND lease_expires_at > :at
                """, error, null);
    }

    private void transition(UUID jobId, UUID leaseToken, Instant at, String sql) {
        transition(jobId, leaseToken, at, sql, null, null);
    }

    private void transition(
            UUID jobId, UUID leaseToken, Instant at, String sql, ParserError error, Instant nextAttemptAt) {
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(leaseToken);
        Objects.requireNonNull(at);
        try {
            var statement = jdbc.sql(sql)
                    .param("id", jobId)
                    .param("leaseToken", leaseToken)
                    .param("at", utc(at));
            if (error != null) statement = statement.param("errorCode", error.code().name());
            if (nextAttemptAt != null) statement = statement.param("nextAttemptAt", utc(micros(nextAttemptAt)));
            if (statement.update() != 1) throw new IngestionLeaseConflictException();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private static IngestionLease mapLease(ResultSet row, int rowNumber) throws SQLException {
        String sourceBlobId = row.getString("source_blob_id");
        var request = new IngestionRequest(
                new DocumentVersionId(row.getObject("document_id", UUID.class), row.getObject("version_id", UUID.class)),
                row.getObject("requested_by", UUID.class), sourceBlobId, row.getString("source_filename"),
                row.getString("media_type"), row.getLong("byte_size"), row.getString("sha256"));
        return new IngestionLease(
                row.getObject("id", UUID.class), row.getObject("lease_token", UUID.class), row.getInt("attempts"),
                row.getString("lease_worker"), row.getObject("lease_expires_at", OffsetDateTime.class).toInstant(), request);
    }

    private static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(micros(instant), ZoneOffset.UTC);
    }
}
