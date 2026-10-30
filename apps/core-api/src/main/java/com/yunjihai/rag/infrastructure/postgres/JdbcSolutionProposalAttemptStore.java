package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.solution.proposal.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Short database claims around a model call; an expired call is never replayed automatically. */
public final class JdbcSolutionProposalAttemptStore implements ProposalAttemptStore {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcSolutionProposalAttemptStore(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
    }

    @Override
    public ProposalRegistration register(ProposalRegistrationRequest request, Duration lease) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(lease);
        long leaseMillis = lease.toMillis();
        if (leaseMillis < 1 || leaseMillis > Duration.ofHours(1).toMillis()) {
            throw new IllegalArgumentException("invalid proposal lease");
        }
        var key = request.key();
        try {
            var created = jdbc.sql("""
                    INSERT INTO solution_model_proposal_attempt
                        (id, actor_id, solution_id, kind, client_key, requirement_version_id,
                         expected_revision, input_fingerprint, status, created_at, updated_at, lease_until)
                    VALUES (:id, :actor, :solution, :kind, :client, :requirement,
                            :revision, :fingerprint, 'RUNNING', statement_timestamp(),
                            statement_timestamp(), statement_timestamp()
                                + CAST(:leaseMillis AS bigint) * INTERVAL '1 millisecond')
                    ON CONFLICT (actor_id, solution_id, kind, client_key) DO NOTHING
                    RETURNING id
                    """).params(keyParams(key))
                    .param("id", UUID.randomUUID())
                    .param("requirement", request.requirementVersionId())
                    .param("revision", request.expectedRevision())
                    .param("fingerprint", request.inputFingerprint())
                    .param("leaseMillis", leaseMillis)
                    .query(UUID.class).optional().isPresent();
            var attempt = findByKey(key).orElseThrow(ProposalRequestConflictException::new);
            if (!attempt.request().equals(request)) throw new ProposalRequestConflictException();
            if (!created) {
                expireKey(key);
                attempt = findByKey(key).orElseThrow(ProposalRequestConflictException::new);
            }
            return new ProposalRegistration(attempt, created);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public Optional<ProposalAttempt> findByKey(ProposalKey key) {
        Objects.requireNonNull(key);
        try {
            return jdbc.sql("""
                    SELECT id, actor_id, solution_id, kind, client_key, requirement_version_id,
                           expected_revision, input_fingerprint, status, result_json::text AS result_json,
                           failure_code, created_at, updated_at, lease_until
                    FROM solution_model_proposal_attempt
                    WHERE actor_id = :actor AND solution_id = :solution
                      AND kind = :kind AND client_key = :client
                    """).params(keyParams(key)).query((row, ignored) -> {
                        var savedKey = new ProposalKey(row.getObject("actor_id", UUID.class),
                                row.getObject("solution_id", UUID.class),
                                ProposalKind.valueOf(row.getString("kind")), row.getString("client_key"));
                        var request = new ProposalRegistrationRequest(savedKey,
                                row.getObject("requirement_version_id", UUID.class),
                                row.getLong("expected_revision"), row.getString("input_fingerprint"));
                        return new ProposalAttempt(row.getObject("id", UUID.class), request,
                                ProposalStatus.valueOf(row.getString("status")),
                                Optional.ofNullable(row.getString("result_json")),
                                Optional.ofNullable(row.getString("failure_code")),
                                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                                row.getObject("updated_at", OffsetDateTime.class).toInstant(),
                                row.getObject("lease_until", OffsetDateTime.class).toInstant());
                    }).optional();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public Optional<ProposalAttempt> findCurrent(ProposalRegistrationRequest request) {
        Objects.requireNonNull(request);
        try {
            jdbc.sql("""
                    UPDATE solution_model_proposal_attempt SET
                        status = 'INDETERMINATE', failure_code = 'MODEL_OUTCOME_UNKNOWN',
                        updated_at = statement_timestamp()
                    WHERE actor_id = :actor AND solution_id = :solution
                      AND kind = :kind AND client_key = :client
                      AND requirement_version_id = :requirement AND expected_revision = :revision
                      AND input_fingerprint = :fingerprint AND status = 'RUNNING'
                      AND lease_until <= statement_timestamp()
                    """).params(keyParams(request.key())).param("requirement", request.requirementVersionId())
                    .param("revision", request.expectedRevision()).param("fingerprint", request.inputFingerprint())
                    .update();
            return findByKey(request.key()).filter(attempt -> attempt.request().equals(request));
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public boolean succeed(ProposalRegistrationRequest request, String resultJson) {
        Objects.requireNonNull(request);
        if (resultJson == null || resultJson.isBlank() || resultJson.length() > 262_144) {
            throw new IllegalArgumentException("invalid proposal result");
        }
        try {
            return Boolean.TRUE.equals(transactions.execute(status -> {
                var id = lockLive(request);
                if (id.isEmpty()) return false;
                return jdbc.sql("""
                    UPDATE solution_model_proposal_attempt SET
                        status = 'SUCCEEDED', result_json = CAST(:result AS jsonb),
                        updated_at = clock_timestamp()
                    WHERE id = :id AND status = 'RUNNING'
                      AND lease_until > clock_timestamp()
                    """).param("id", id.orElseThrow()).param("result", resultJson).update() == 1;
            }));
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public boolean fail(ProposalRegistrationRequest request, String failureCode) {
        Objects.requireNonNull(request);
        if (failureCode == null || !failureCode.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("invalid proposal failure code");
        }
        try {
            return Boolean.TRUE.equals(transactions.execute(status -> {
                var id = lockLive(request);
                if (id.isEmpty()) return false;
                return jdbc.sql("""
                    UPDATE solution_model_proposal_attempt SET
                        status = 'FAILED', failure_code = :failureCode,
                        updated_at = clock_timestamp()
                    WHERE id = :id AND status = 'RUNNING'
                      AND lease_until > clock_timestamp()
                    """).param("id", id.orElseThrow()).param("failureCode", failureCode).update() == 1;
            }));
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private Optional<UUID> lockLive(ProposalRegistrationRequest request) {
        var locked = jdbc.sql("""
                SELECT id, lease_until FROM solution_model_proposal_attempt
                WHERE actor_id = :actor AND solution_id = :solution
                  AND kind = :kind AND client_key = :client
                  AND requirement_version_id = :requirement
                  AND expected_revision = :revision AND input_fingerprint = :fingerprint
                  AND status = 'RUNNING'
                FOR UPDATE
                """).params(keyParams(request.key())).param("requirement", request.requirementVersionId())
                .param("revision", request.expectedRevision())
                .param("fingerprint", request.inputFingerprint())
                .query((row, ignored) -> new Lease(row.getObject("id", UUID.class),
                        row.getObject("lease_until", OffsetDateTime.class).toInstant()))
                .optional();
        if (locked.isEmpty()) return Optional.empty();
        var now = jdbc.sql("SELECT clock_timestamp()")
                .query(OffsetDateTime.class).single().toInstant();
        return now.isBefore(locked.orElseThrow().until())
                ? Optional.of(locked.orElseThrow().id()) : Optional.empty();
    }

    private record Lease(UUID id, java.time.Instant until) { }

    @Override
    public int expireStale(int batchSize) {
        if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException("invalid proposal scan size");
        try {
            return jdbc.sql("""
                    WITH expired AS (
                        SELECT id FROM solution_model_proposal_attempt
                        WHERE status = 'RUNNING' AND lease_until <= statement_timestamp()
                        ORDER BY lease_until, id LIMIT :batchSize FOR UPDATE SKIP LOCKED
                    )
                    UPDATE solution_model_proposal_attempt AS attempt SET
                        status = 'INDETERMINATE', failure_code = 'MODEL_OUTCOME_UNKNOWN',
                        updated_at = statement_timestamp()
                    FROM expired WHERE attempt.id = expired.id
                    """).param("batchSize", batchSize).update();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private void expireKey(ProposalKey key) {
        jdbc.sql("""
                UPDATE solution_model_proposal_attempt SET
                    status = 'INDETERMINATE', failure_code = 'MODEL_OUTCOME_UNKNOWN',
                    updated_at = statement_timestamp()
                WHERE actor_id = :actor AND solution_id = :solution
                  AND kind = :kind AND client_key = :client
                  AND status = 'RUNNING' AND lease_until <= statement_timestamp()
                """).params(keyParams(key)).update();
    }

    private static Map<String, Object> keyParams(ProposalKey key) {
        return Map.of("actor", key.actorId(), "solution", key.solutionId(),
                "kind", key.kind().name(), "client", key.clientKey());
    }
}
