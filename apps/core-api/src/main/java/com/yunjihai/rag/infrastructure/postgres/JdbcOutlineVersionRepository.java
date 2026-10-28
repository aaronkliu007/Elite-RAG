package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.port.OutlineVersionRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.time.*;
import java.util.*;

public final class JdbcOutlineVersionRepository implements OutlineVersionRepository {
    private final JdbcClient jdbc;
    public JdbcOutlineVersionRepository(JdbcClient jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public long nextNumber(UUID solutionId) {
        Objects.requireNonNull(solutionId);
        try {
            return jdbc.sql("SELECT COALESCE(MAX(number), 0) + 1 FROM solution_outline_version WHERE solution_id = :solution")
                    .param("solution", solutionId).query(Long.class).single();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override public void insert(OutlineVersion version) {
        throw new IllegalArgumentException("solution id is required");
    }

    @Override public void insert(UUID solutionId, OutlineVersion version) {
        Objects.requireNonNull(solutionId);
        Objects.requireNonNull(version);
        try {
            jdbc.sql("""
                    INSERT INTO solution_outline_version (id, solution_id, number, created_by, created_at, change_reason)
                    VALUES (:id, :solution, :number, :actor, :at, :reason)
                    """).param("id", version.id()).param("solution", solutionId).param("number", version.number())
                    .param("actor", version.createdBy()).param("at", at(version.createdAt()))
                    .param("reason", version.changeReason()).update();
            for (var node : version.nodes()) {
                jdbc.sql("""
                        INSERT INTO solution_outline_node
                            (version_id, section_id, node_order, title, level, required, objective, retrieval_hint)
                        VALUES (:version, :section, :position, :title, :level, :required, :objective, :hint)
                        """).param("version", version.id()).param("section", node.sectionId())
                        .param("position", node.order()).param("title", node.title()).param("level", node.level())
                        .param("required", node.required()).param("objective", node.objective())
                        .param("hint", node.retrievalHint()).update();
                for (var risk : node.riskTypes()) {
                    jdbc.sql("""
                            INSERT INTO solution_outline_node_risk (version_id, section_id, risk_type)
                            VALUES (:version, :section, :risk)
                            """).param("version", version.id()).param("section", node.sectionId())
                            .param("risk", risk.name()).update();
                }
            }
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override public Optional<OutlineVersion> findById(UUID versionId) {
        Objects.requireNonNull(versionId);
        try {
            return jdbc.sql("""
                    SELECT id, number, created_by, created_at, change_reason FROM solution_outline_version WHERE id = :id
                    """).param("id", versionId).query((row, ignored) -> new OutlineVersion(
                            row.getObject("id", UUID.class), row.getLong("number"), nodes(versionId),
                            row.getObject("created_by", UUID.class),
                            row.getObject("created_at", OffsetDateTime.class).toInstant(),
                            row.getString("change_reason"))).optional();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private List<OutlineNode> nodes(UUID versionId) {
        return jdbc.sql("""
                SELECT section_id, title, level, node_order, required, objective, retrieval_hint
                FROM solution_outline_node WHERE version_id = :version ORDER BY node_order
                """).param("version", versionId).query((row, ignored) -> new OutlineNode(
                        row.getObject("section_id", UUID.class), row.getString("title"),
                        row.getInt("level"), row.getInt("node_order"), row.getBoolean("required"),
                        row.getString("objective"), row.getString("retrieval_hint"),
                        risks(versionId, row.getObject("section_id", UUID.class)))).list();
    }

    private Set<RiskType> risks(UUID versionId, UUID sectionId) {
        var values = jdbc.sql("""
                SELECT risk_type FROM solution_outline_node_risk
                WHERE version_id = :version AND section_id = :section
                """).param("version", versionId).param("section", sectionId)
                .query(String.class).list();
        Set<RiskType> result = new HashSet<>();
        values.forEach(value -> result.add(RiskType.valueOf(value)));
        return Set.copyOf(result);
    }

    private static OffsetDateTime at(Instant instant) { return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
