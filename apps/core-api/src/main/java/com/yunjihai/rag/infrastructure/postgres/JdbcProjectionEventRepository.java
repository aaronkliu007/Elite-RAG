package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.common.UuidV7;
import com.yunjihai.rag.retrieval.projection.ProjectionEvent;
import com.yunjihai.rag.retrieval.projection.ProjectionEventRepository;
import com.yunjihai.rag.retrieval.projection.ProjectionEventStatus;
import com.yunjihai.rag.retrieval.projection.ProjectionLease;
import com.yunjihai.rag.retrieval.projection.ProjectionLeaseConflictException;
import com.yunjihai.rag.retrieval.projection.ProjectionScope;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL-backed durable projection outbox with token-bound recoverable leases. */
public final class JdbcProjectionEventRepository implements ProjectionEventRepository {
    private final JdbcClient jdbc;

    public JdbcProjectionEventRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public long enqueueDocument(
            UUID spaceId, UUID documentId, UUID versionId, long documentRevision, Instant at) {
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(at);
        if (documentRevision < 0) throw new IllegalArgumentException("文档修订号不能为负数");
        try {
            var statement = jdbc.sql("""
                    INSERT INTO retrieval_projection_event (
                        id, scope_type, space_id, document_id, version_id, document_revision,
                        status, attempts, next_attempt_at, created_at, updated_at)
                    VALUES (:id, 'DOCUMENT', :spaceId, :documentId, :versionId, :revision,
                            'PENDING', 0, :at, :at, :at)
                    RETURNING sequence
                    """)
                    .param("id", UuidV7.generate(at))
                    .param("spaceId", spaceId)
                    .param("documentId", documentId)
                    .param("revision", documentRevision)
                    .param("at", utc(at));
            statement = versionId == null
                    ? statement.param("versionId", null, java.sql.Types.OTHER)
                    : statement.param("versionId", versionId);
            return statement
                    .query(Long.class)
                    .single();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public long enqueueSpace(UUID spaceId, Instant at) {
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(at);
        try {
            return jdbc.sql("""
                    INSERT INTO retrieval_projection_event (
                        id, scope_type, space_id, status, attempts,
                        next_attempt_at, created_at, updated_at)
                    VALUES (:id, 'SPACE', :spaceId, 'PENDING', 0, :at, :at, :at)
                    RETURNING sequence
                    """)
                    .param("id", UuidV7.generate(at))
                    .param("spaceId", spaceId)
                    .param("at", utc(at))
                    .query(Long.class)
                    .single();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public List<ProjectionLease> claimBatch(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 255) {
            throw new IllegalArgumentException("Worker 标识无效");
        }
        if (limit <= 0 || limit > 1_000) throw new IllegalArgumentException("领取批量无效");
        Objects.requireNonNull(now);
        Objects.requireNonNull(leaseUntil);
        Instant normalizedNow = micros(now);
        Instant normalizedLeaseUntil = micros(leaseUntil);
        if (!normalizedLeaseUntil.isAfter(normalizedNow)) {
            throw new IllegalArgumentException("租约截止时间必须晚于领取时间");
        }
        UUID leaseToken = UUID.randomUUID();
        try {
            var leases = jdbc.sql("""
                    WITH candidate AS (
                        SELECT id
                        FROM retrieval_projection_event
                        WHERE (status IN ('PENDING', 'RETRY_WAIT') AND next_attempt_at <= :now)
                           OR (status = 'LEASED' AND lease_expires_at <= :now)
                        ORDER BY sequence
                        FOR UPDATE SKIP LOCKED
                        LIMIT :limit
                    )
                    UPDATE retrieval_projection_event AS event
                    SET status = 'LEASED', attempts = event.attempts + 1,
                        lease_token = :leaseToken, lease_worker = :workerId,
                        lease_expires_at = :leaseUntil, error_code = NULL, updated_at = :now
                    FROM candidate
                    WHERE event.id = candidate.id
                    RETURNING event.id, event.sequence, event.scope_type, event.space_id,
                              event.document_id, event.version_id, event.document_revision,
                              event.progress_document_id, event.lease_token, event.attempts,
                              event.lease_worker, event.lease_expires_at
                    """)
                    .param("now", utc(normalizedNow))
                    .param("limit", limit)
                    .param("leaseToken", leaseToken)
                    .param("workerId", workerId)
                    .param("leaseUntil", utc(normalizedLeaseUntil))
                    .query(JdbcProjectionEventRepository::mapLease)
                    .list();
            return leases.stream().sorted(Comparator.comparingLong(ProjectionLease::sequence)).toList();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public void renew(UUID eventId, UUID leaseToken, Instant at, Instant leaseUntil) {
        requireFutureLease(at, leaseUntil);
        transition(jdbc.sql("""
                UPDATE retrieval_projection_event
                SET lease_expires_at = GREATEST(lease_expires_at, :leaseUntil), updated_at = :at
                WHERE id = :id AND status = 'LEASED' AND lease_token = :leaseToken
                  AND lease_expires_at > :at
                """).param("leaseUntil", utc(leaseUntil)), eventId, leaseToken, at);
    }

    @Override
    public void checkpoint(
            UUID eventId, UUID leaseToken, UUID progressDocumentId, Instant at, Instant leaseUntil) {
        Objects.requireNonNull(progressDocumentId);
        requireFutureLease(at, leaseUntil);
        transition(jdbc.sql("""
                UPDATE retrieval_projection_event
                SET progress_document_id = :progressDocumentId,
                    lease_expires_at = GREATEST(lease_expires_at, :leaseUntil), updated_at = :at
                WHERE id = :id AND scope_type = 'SPACE' AND status = 'LEASED'
                  AND lease_token = :leaseToken AND lease_expires_at > :at
                """).param("progressDocumentId", progressDocumentId)
                .param("leaseUntil", utc(leaseUntil)), eventId, leaseToken, at);
    }

    @Override
    public void complete(UUID eventId, UUID leaseToken, Instant at) {
        transition(jdbc.sql("""
                UPDATE retrieval_projection_event
                SET status = 'SUCCEEDED', lease_token = NULL, lease_worker = NULL,
                    lease_expires_at = NULL, error_code = NULL, updated_at = :at
                WHERE id = :id AND status = 'LEASED' AND lease_token = :leaseToken
                  AND lease_expires_at > :at
                """), eventId, leaseToken, at);
    }

    @Override
    public void retry(
            UUID eventId, UUID leaseToken, String errorCode, Instant nextAttemptAt, Instant at) {
        validateErrorCode(errorCode);
        Objects.requireNonNull(nextAttemptAt);
        if (micros(nextAttemptAt).isBefore(micros(at))) {
            throw new IllegalArgumentException("下次尝试时间不能早于当前时间");
        }
        transition(jdbc.sql("""
                UPDATE retrieval_projection_event
                SET status = 'RETRY_WAIT', next_attempt_at = :nextAttemptAt,
                    lease_token = NULL, lease_worker = NULL, lease_expires_at = NULL,
                    error_code = :errorCode, updated_at = :at
                WHERE id = :id AND status = 'LEASED' AND lease_token = :leaseToken
                  AND lease_expires_at > :at
                """).param("nextAttemptAt", utc(nextAttemptAt)).param("errorCode", errorCode),
                eventId, leaseToken, at);
    }

    @Override
    public void fail(UUID eventId, UUID leaseToken, String errorCode, Instant at) {
        validateErrorCode(errorCode);
        transition(jdbc.sql("""
                UPDATE retrieval_projection_event
                SET status = 'FAILED', lease_token = NULL, lease_worker = NULL,
                    lease_expires_at = NULL, error_code = :errorCode, updated_at = :at
                WHERE id = :id AND status = 'LEASED' AND lease_token = :leaseToken
                  AND lease_expires_at > :at
                """).param("errorCode", errorCode), eventId, leaseToken, at);
    }

    @Override
    public long currentSequence() {
        try {
            return jdbc.sql("SELECT COALESCE(MAX(sequence), 0) FROM retrieval_projection_event")
                    .query(Long.class)
                    .single();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public List<ProjectionEvent> eventsAfter(long sequence, int limit) {
        if (sequence < 0 || limit <= 0 || limit > 10_000) {
            throw new IllegalArgumentException("回放窗口无效");
        }
        try {
            return jdbc.sql("""
                    SELECT id, sequence, scope_type, space_id, document_id, version_id,
                           document_revision, progress_document_id, status, attempts,
                           next_attempt_at, error_code, created_at, updated_at
                    FROM retrieval_projection_event
                    WHERE sequence > :sequence
                    ORDER BY sequence
                    LIMIT :limit
                    """)
                    .param("sequence", sequence)
                    .param("limit", limit)
                    .query(JdbcProjectionEventRepository::mapEvent)
                    .list();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private void transition(
            JdbcClient.StatementSpec statement, UUID eventId, UUID leaseToken, Instant at) {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(leaseToken);
        Objects.requireNonNull(at);
        try {
            int updated = statement
                    .param("id", eventId)
                    .param("leaseToken", leaseToken)
                    .param("at", utc(at))
                    .update();
            if (updated != 1) throw new ProjectionLeaseConflictException();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private static ProjectionLease mapLease(ResultSet row, int rowNumber) throws SQLException {
        return new ProjectionLease(
                row.getObject("id", UUID.class),
                row.getLong("sequence"),
                ProjectionScope.valueOf(row.getString("scope_type")),
                row.getObject("space_id", UUID.class),
                row.getObject("document_id", UUID.class),
                row.getObject("version_id", UUID.class),
                nullableLong(row, "document_revision"),
                row.getObject("progress_document_id", UUID.class),
                row.getObject("lease_token", UUID.class),
                row.getInt("attempts"),
                row.getString("lease_worker"),
                row.getObject("lease_expires_at", OffsetDateTime.class).toInstant());
    }

    private static ProjectionEvent mapEvent(ResultSet row, int rowNumber) throws SQLException {
        return new ProjectionEvent(
                row.getObject("id", UUID.class),
                row.getLong("sequence"),
                ProjectionScope.valueOf(row.getString("scope_type")),
                row.getObject("space_id", UUID.class),
                row.getObject("document_id", UUID.class),
                row.getObject("version_id", UUID.class),
                nullableLong(row, "document_revision"),
                row.getObject("progress_document_id", UUID.class),
                ProjectionEventStatus.valueOf(row.getString("status")),
                row.getInt("attempts"),
                row.getObject("next_attempt_at", OffsetDateTime.class).toInstant(),
                row.getString("error_code"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static void validateErrorCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank() || errorCode.length() > 100) {
            throw new IllegalArgumentException("投影错误码无效");
        }
    }

    private static void requireFutureLease(Instant at, Instant leaseUntil) {
        Objects.requireNonNull(at);
        Objects.requireNonNull(leaseUntil);
        if (!micros(leaseUntil).isAfter(micros(at))) {
            throw new IllegalArgumentException("租约续期截止时间必须晚于当前时间");
        }
    }

    private static Instant micros(Instant instant) {
        return Objects.requireNonNull(instant).truncatedTo(ChronoUnit.MICROS);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(micros(instant), ZoneOffset.UTC);
    }
}
