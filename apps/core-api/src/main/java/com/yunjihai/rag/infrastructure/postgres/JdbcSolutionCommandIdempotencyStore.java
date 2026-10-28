package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.solution.application.*;
import com.yunjihai.rag.solution.domain.SolutionPhase;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.*;

public final class JdbcSolutionCommandIdempotencyStore implements SolutionCommandIdempotencyStore {
    private final JdbcClient jdbc;
    public JdbcSolutionCommandIdempotencyStore(JdbcClient jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public Optional<SolutionCommandResult> claim(Key key, String digest) {
        Objects.requireNonNull(key);
        if (digest == null || !digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid digest");
        try {
            jdbc.sql("""
                    INSERT INTO solution_command_idempotency (actor_id, resource_id, action, client_key, request_digest)
                    VALUES (:actor, :resource, :action, :client, :digest)
                    ON CONFLICT (actor_id, resource_id, action, client_key) DO NOTHING
                    """).params(params(key)).param("digest", digest).update();
            var row = jdbc.sql("""
                    SELECT request_digest, result_solution_id, result_revision, result_requirement_version_id,
                           result_outline_version_id, result_phase
                    FROM solution_command_idempotency
                    WHERE actor_id = :actor AND resource_id = :resource AND action = :action AND client_key = :client
                    FOR UPDATE
                    """).params(params(key)).query((result, ignored) -> new State(
                            result.getString("request_digest"),
                            result.getObject("result_solution_id", UUID.class),
                            result.getObject("result_revision", Long.class),
                            result.getObject("result_requirement_version_id", UUID.class),
                            result.getObject("result_outline_version_id", UUID.class),
                            result.getString("result_phase"))).single();
            if (!digest.equals(row.digest())) throw new SolutionCommandConflictException("IDEMPOTENCY_CONFLICT");
            if (row.solutionId() == null) return Optional.empty();
            return Optional.of(new SolutionCommandResult(row.solutionId(), row.revision(), row.requirementVersionId(),
                    Optional.ofNullable(row.outlineVersionId()), SolutionPhase.valueOf(row.phase())));
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override public void complete(Key key, SolutionCommandResult result) {
        Objects.requireNonNull(key); Objects.requireNonNull(result);
        try {
            int count = jdbc.sql("""
                    UPDATE solution_command_idempotency
                    SET result_solution_id = :solution, result_revision = :revision,
                        result_requirement_version_id = :requirement, result_outline_version_id = :outline,
                        result_phase = :phase
                    WHERE actor_id = :actor AND resource_id = :resource AND action = :action AND client_key = :client
                      AND result_solution_id IS NULL
                    """).params(params(key)).param("solution", result.solutionId())
                    .param("revision", result.revision()).param("requirement", result.requirementVersionId())
                    .param("outline", result.outlineVersionId().orElse(null)).param("phase", result.phase().name())
                    .update();
            if (count != 1) throw new SolutionCommandConflictException("IDEMPOTENCY_CONFLICT");
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override public void release(Key key) {
        Objects.requireNonNull(key);
        try {
            jdbc.sql("""
                    DELETE FROM solution_command_idempotency
                    WHERE actor_id = :actor AND resource_id = :resource AND action = :action AND client_key = :client
                      AND result_solution_id IS NULL
                    """).params(params(key)).update();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private static Map<String, Object> params(Key key) {
        return Map.of("actor", key.actorId(), "resource", key.resourceId(),
                "action", key.action(), "client", key.clientKey());
    }

    private record State(String digest, UUID solutionId, Long revision, UUID requirementVersionId,
            UUID outlineVersionId, String phase) { }
}
