package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.solution.feedback.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcFeedbackStore implements FeedbackStore {
    private final JdbcClient jdbc;

    public JdbcFeedbackStore(JdbcClient jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public Optional<SolutionFeedback> findByKey(UUID actorId, UUID solutionId, String clientKey) {
        try {
            return jdbc.sql("""
                    SELECT * FROM solution_feedback WHERE actor_id = :actor
                      AND solution_id = :solution AND client_key = :key
                    """).param("actor", actorId).param("solution", solutionId).param("key", clientKey)
                    .query(JdbcFeedbackStore::map).optional();
        } catch (DataAccessException failure) { throw PostgresPersistenceExceptionTranslator.translate(failure); }
    }

    @Override public SolutionFeedback insertIfAbsent(SolutionFeedback value) {
        try {
            var inserted = jdbc.sql("""
                    INSERT INTO solution_feedback
                      (id, solution_id, finalized_version_id, section_version_id, actor_id,
                       reason, client_key, created_at)
                    VALUES (:id, :solution, :version, :section, :actor, :reason, :key, :at)
                    ON CONFLICT (actor_id, solution_id, client_key) DO NOTHING
                    RETURNING id
                    """).param("id", value.id()).param("solution", value.solutionId())
                    .param("version", value.finalizedVersionId()).param("section", value.sectionVersionId())
                    .param("actor", value.actorId()).param("reason", value.reason().name())
                    .param("key", value.clientKey())
                    .param("at", OffsetDateTime.ofInstant(value.createdAt(), ZoneOffset.UTC))
                    .query(UUID.class).optional();
            return inserted.isPresent() ? value : findByKey(value.actorId(), value.solutionId(), value.clientKey())
                    .orElseThrow(() -> new IllegalStateException("feedback conflict without row"));
        } catch (DataAccessException failure) { throw PostgresPersistenceExceptionTranslator.translate(failure); }
    }

    @Override public List<SolutionFeedback> listByActorAndVersion(UUID actorId, UUID versionId) {
        try {
            return jdbc.sql("""
                    SELECT * FROM solution_feedback
                    WHERE actor_id = :actor AND finalized_version_id = :version
                    ORDER BY created_at DESC, id DESC LIMIT 100
                    """).param("actor", actorId).param("version", versionId)
                    .query(JdbcFeedbackStore::map).list();
        } catch (DataAccessException failure) { throw PostgresPersistenceExceptionTranslator.translate(failure); }
    }

    private static SolutionFeedback map(ResultSet row, int ignored) throws SQLException {
        return new SolutionFeedback(row.getObject("id", UUID.class),
                row.getObject("solution_id", UUID.class),
                row.getObject("finalized_version_id", UUID.class),
                row.getObject("section_version_id", UUID.class),
                row.getObject("actor_id", UUID.class),
                FeedbackReason.valueOf(row.getString("reason")), row.getString("client_key"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
